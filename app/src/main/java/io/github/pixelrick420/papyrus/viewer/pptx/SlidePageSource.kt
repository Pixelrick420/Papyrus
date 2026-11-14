package io.github.pixelrick420.papyrus.viewer.pptx

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.PageBitmapSource
import io.github.pixelrick420.papyrus.viewer.bitmapCacheBudgetBytes
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideImage
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlidePictureResolver
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideRenderer
import io.github.pixelrick420.papyrus.viewer.pptx.render.SoftwareCanvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Renders slides through [SlideRenderer] into the bitmap cache the page viewer draws from. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SlidePageSource private constructor(
    private val pkg: PptxPackage,
    private val slides: List<SlideScene>,
    private val mediaDir: File,
    private val limits: PptxLimits,
    private val renderer: SlideRenderer,
) : PageBitmapSource {

    override val pageCount: Int = slides.size

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var closed = false

    private val cacheBudgetBytes = bitmapCacheBudgetBytes()

    override val maxRenderPixels: Int = cacheBudgetBytes / (BYTES_PER_PIXEL * PAGES_PER_CACHE)

    private val images = ImageCache(maxPixels = limits.maxImagePixels, maxEdgePx = limits.maxRenderEdgePx)

    private val cache = object : LruCache<PageKey, Bitmap>(cacheBudgetBytes) {
        override fun sizeOf(key: PageKey, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    // Every slide shares the presentation size, so aspect ratios are known without opening a slide.
    override suspend fun loadAspectRatios(): List<Float> {
        val size = slides.firstOrNull()?.scene?.size ?: return emptyList()
        val ratio = slideAspectRatio(size)
        return List(pageCount) { ratio }
    }

    override fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(PageKey(index, widthPx))

    override fun evictOutside(keep: IntRange) {
        for (key in cache.snapshot().keys) {
            if (key.index !in keep) cache.remove(key)
        }
    }

    override fun trimMemory(evictAll: Boolean) {
        if (evictAll) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
        images.trimMemory(evictAll)
    }

    // Renders queue behind one another; a render already running cannot be interrupted and finishes into the cache.
    // A draw failure drops one slide, not the deck, and its class is all a redacted log can carry.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = PageKey(index, widthPx)
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                ensureActive()
                val slide = slides.getOrNull(index) ?: return@withLock null
                try {
                    rasterize(slide, widthPx)?.also { cache.put(key, it) }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: OutOfMemoryError) {
                    logFailure(index, OutOfMemoryError::class.java.simpleName)
                    null
                } catch (error: RuntimeException) {
                    logFailure(index, error)
                    null
                }
            }
        }
    }

    private suspend fun rasterize(slide: SlideScene, widthPx: Int): Bitmap? {
        val size = slide.scene.size
        if (size.width <= 0.0 || size.height <= 0.0 || widthPx <= 0) return null
        val heightPx = slideHeightPx(widthPx, size, limits.maxRenderEdgePx)
        val scale = widthPx.toFloat() / size.width.toFloat()
        val pictures = PictureImages(pkg, slide.part, images)
        val resolver = SlidePictureResolver { picture ->
            pictures.raster(picture.source)?.let { SlideImage(it.file, it.bounds) }
        }
        val list = renderer.render(slide.scene, resolver) ?: return null
        val decoded = HashMap<String, Bitmap?>()
        return SoftwareCanvas.render(list, widthPx, heightPx, scale) { ref ->
            if (decoded.containsKey(ref.key)) {
                decoded[ref.key]
            } else {
                images.load(File(ref.key), widthPx)?.bitmap.also { decoded[ref.key] = it }
            }
        }
    }

    /** The slide index and failure class only: a slide can hold text or names the reader owns. */
    private fun logFailure(index: Int, error: Throwable) = logFailure(index, error.javaClass.simpleName)

    private fun logFailure(index: Int, name: String) {
        Log.w(TAG, "Slide $index unavailable: $name")
    }

    override fun close() {
        synchronized(openSources) { openSources.remove(this) }
        CoroutineScope(dispatcher).launch {
            runCatching {
                lock.withLock {
                    if (closed) return@withLock
                    closed = true
                    runCatching { cache.evictAll() }
                    runCatching { images.clear() }
                    runCatching { mediaDir.deleteRecursively() }
                }
            }
        }
    }

    private data class PageKey(val index: Int, val widthPx: Int)

    @Suppress("TooGenericExceptionCaught")
    companion object {
        private const val TAG = "SlidePageSource"

        private const val BYTES_PER_PIXEL = 4

        private const val PAGES_PER_CACHE = 3

        private val openSources: MutableSet<SlidePageSource> =
            Collections.newSetFromMap(WeakHashMap<SlidePageSource, Boolean>())

        fun trimAll(evictAll: Boolean) {
            val sources = synchronized(openSources) { openSources.toList() }
            for (source in sources) source.trimMemory(evictAll)
        }

        suspend fun open(context: Context, uri: Uri): SlidePageSource = withContext(Dispatchers.IO) {
            val dir = mediaDir(context)
            try {
                openStream(
                    open = {
                        context.contentResolver.openInputStream(uri)
                            ?: throw FileNotFoundException(uri.toString())
                    },
                    mediaDir = dir,
                    presets = presets(context),
                )
            } catch (error: Exception) {
                dir.deleteRecursively()
                throw error
            }
        }

        suspend fun openFile(context: Context, file: File): SlidePageSource = withContext(Dispatchers.IO) {
            val dir = mediaDir(context)
            try {
                openStream(open = { file.inputStream() }, mediaDir = dir, presets = presets(context))
            } catch (error: Exception) {
                dir.deleteRecursively()
                throw error
            }
        }

        internal suspend fun openStream(
            open: () -> InputStream,
            mediaDir: File,
            presets: PresetGeometry,
            limits: PptxLimits = PptxLimits(),
            newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
        ): SlidePageSource = withContext(Dispatchers.IO) {
            val pkg = PptxPackage.read(open, mediaDir, limits, newParserFactory)
            val main = pkg.mainPart
            val bytes = pkg.part(main) ?: throw PptxFormatException("$main is missing")
            val presentation = PresentationParser(newParserFactory, limits).parse(bytes, main)
            val scenes = StyleResolver(newParserFactory, limits).resolveAll(pkg, presentation)
            SlidePageSource(
                pkg = pkg,
                slides = scenes.slides,
                mediaDir = mediaDir,
                limits = limits,
                renderer = SlideRenderer(presets, limits),
            ).also { synchronized(openSources) { openSources.add(it) } }
        }

        private fun mediaDir(context: Context): File =
            File(context.cacheDir, "pptx-${UUID.randomUUID()}")

        private fun presets(context: Context): PresetGeometry =
            PresetGeometry(open = { context.assets.open("ooxml/presetShapeDefinitions.xml") })
    }
}

internal fun slideAspectRatio(size: SceneSize): Float =
    if (size.width > 0.0) (size.height / size.width).toFloat() else 0f

internal fun slideHeightPx(widthPx: Int, size: SceneSize, maxEdgePx: Int): Int =
    (widthPx.toDouble() * size.height / size.width).roundToInt().coerceIn(1, maxEdgePx)
