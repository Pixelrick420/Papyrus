package io.github.pixelrick420.papyrus.viewer.pptx

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import android.util.Xml
import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.PageBitmapSource
import io.github.pixelrick420.papyrus.viewer.TileGrid
import io.github.pixelrick420.papyrus.viewer.bitmapCacheBudgetBytes
import io.github.pixelrick420.papyrus.viewer.pptx.render.DisplayList
import io.github.pixelrick420.papyrus.viewer.pptx.render.RectF
import io.github.pixelrick420.papyrus.viewer.pptx.render.RenderRegion
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideImage
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlidePictureResolver
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideRenderer
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideTextPainter
import io.github.pixelrick420.papyrus.viewer.pptx.render.SoftwareCanvas
import io.github.pixelrick420.papyrus.viewer.pptx.meta.AndroidMetafileDecoder
import io.github.pixelrick420.papyrus.viewer.pptx.meta.MetafileDecoder
import io.github.pixelrick420.papyrus.viewer.pptx.recover
import io.github.pixelrick420.papyrus.viewer.pptx.text.EmbeddedFontDecoder
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontCatalog
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontSlots
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontSubstitution
import io.github.pixelrick420.papyrus.viewer.pptx.text.PaintTextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextShaper
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextWarpRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Renders slides through [SlideRenderer] into the bitmap cache the page viewer draws from. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SlidePageSource private constructor(
    internal val pkg: PptxPackage,
    private val scenes: LazyDeckScenes,
    internal val presentationInfo: PresentationInfo,
    private val mediaDir: File,
    private val limits: PptxLimits,
    private val renderer: SlideRenderer,
    private val fonts: FontCatalog? = null,
    val fontSubstitution: FontSubstitution,
    private val generated: MutableMap<String, RasterPicture> = HashMap(),
    private val placeholderLabels: PictureLabels = PictureLabels.Default,
) : PageBitmapSource {

    private val _textIndexFlow = MutableStateFlow(SlideTextIndex(emptyList()))

    val textIndexFlow: StateFlow<SlideTextIndex> = _textIndexFlow.asStateFlow()

    val textIndex: SlideTextIndex get() = _textIndexFlow.value

    /** Seeded from each slide's root tag, so hidden badges do not wait for the full index. */
    val hidden: Set<Int> get() = scenes.hiddenSnapshot

    private val textIndexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var textIndexJob: Job? = null

    fun setTextIndex(index: SlideTextIndex) {
        _textIndexFlow.value = index
    }

    fun startTextIndexBuild(build: (check: () -> Unit) -> SlideTextIndex) {
        textIndexJob = textIndexScope.launchTextIndexBuild(build) { _textIndexFlow.value = it }
    }

    override val pageCount: Int = scenes.count

    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val lock = Mutex()
    private var closed = false

    private val cacheBudgetBytes = bitmapCacheBudgetBytes()

    override val maxRenderPixels: Int = cacheBudgetBytes / (BYTES_PER_PIXEL * PAGES_PER_CACHE)

    private val incompleteFlow = MutableStateFlow(false)
    override val incomplete: StateFlow<Boolean> = incompleteFlow.asStateFlow()

    /** Package drops, truncated slide lists, failed rels and truncated renders are worth telling the reader about. */
    fun isIncomplete(): Boolean =
        pkg.mediaDropped > 0 || pkg.relFailures > 0 || presentationInfo.slidesTruncated ||
            scenes.incomplete || incompleteFlow.value

    private val images = ImageCache(maxPixels = limits.maxImagePixels, maxEdgePx = limits.maxRenderEdgePx)

    private val metafiles: MetafileDecoder = AndroidMetafileDecoder(limits.maxRenderEdgePx, limits.maxImagePixels)

    private val lists = object : LruCache<Int, DisplayList>(MAX_CACHED_LISTS) {}

    private val incompleteRenders = ConcurrentHashMap.newKeySet<Int>()

    private val cache = object : LruCache<CacheKey, Bitmap>(cacheBudgetBytes) {
        override fun sizeOf(key: CacheKey, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
    }

    // Every slide shares the presentation size, so aspect ratios are known without opening a slide.
    override suspend fun loadAspectRatios(): List<Float> {
        val ratio = slideAspectRatio(scenes.size)
        return List(pageCount) { ratio }
    }

    override fun cached(index: Int, widthPx: Int): Bitmap? = cache.get(CacheKey.Page(index, widthPx))

    override fun evictOutside(keep: IntRange) {
        for (key in cache.snapshot().keys) {
            if (key.index !in keep) cache.remove(key)
        }
    }

    override fun trimMemory(evictAll: Boolean) {
        if (evictAll) cache.evictAll() else cache.trimToSize(cache.maxSize() / 2)
        if (evictAll) lists.evictAll() else lists.trimToSize(lists.maxSize() / 2)
        if (evictAll) synchronized(generated) { generated.clear() }
        scenes.trimMemory(evictAll)
        images.trimMemory(evictAll)
        metafiles.trimMemory(evictAll)
    }

    // Renders queue behind one another; a render already running cannot be interrupted and finishes into the cache.
    // A draw failure drops one slide, not the deck, and its class is all a redacted log can carry.
    @Suppress("TooGenericExceptionCaught")
    override suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = CacheKey.Page(index, widthPx)
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                ensureActive()
                val slide = withContext(Dispatchers.IO) { scenes.scene(index) } ?: return@withLock null
                try {
                    rasterize(index, slide, widthPx)?.also { cache.put(key, it) }
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

    override fun tileGrid(index: Int, widthPx: Int): TileGrid? {
        if (index !in 0 until pageCount) return null
        val heightPx = slideHeightPx(widthPx, scenes.size, limits.maxRenderEdgePx)
        return TileGrid.forPage(widthPx, heightPx, maxRenderPixels)
    }

    override fun cachedTile(index: Int, widthPx: Int, rect: NormRect): Bitmap? =
        cache.get(CacheKey.Tile(index, widthPx, rect))

    @Suppress("TooGenericExceptionCaught")
    override suspend fun renderTile(index: Int, widthPx: Int, rect: NormRect): Bitmap? {
        val key = CacheKey.Tile(index, widthPx, rect)
        cache.get(key)?.let { return it }
        return withContext(dispatcher) {
            lock.withLock {
                if (closed) return@withLock null
                cache.get(key)?.let { return@withLock it }
                ensureActive()
                val slide = withContext(Dispatchers.IO) { scenes.scene(index) } ?: return@withLock null
                try {
                    rasterizeTile(index, slide, widthPx, rect)?.also { cache.put(key, it) }
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

    private suspend fun rasterize(index: Int, slide: SlideScene, widthPx: Int): Bitmap? {
        val size = slide.scene.size
        if (size.width <= 0.0 || size.height <= 0.0 || widthPx <= 0) return null
        val render = renderSize(size, widthPx)
        val list = displayListOf(index, slide)
        val decoded = HashMap<String, Bitmap?>()
        return SoftwareCanvas.render(
            list = list,
            widthPx = render.widthPx,
            heightPx = render.heightPx,
            scale = render.scale,
            imageOf = { ref ->
                ref.bitmap ?: if (decoded.containsKey(ref.key)) {
                    decoded[ref.key]
                } else {
                    images.load(File(ref.key), render.widthPx)?.bitmap.also { decoded[ref.key] = it }
                }
            },
            fonts = fonts,
        )
    }

    private suspend fun rasterizeTile(index: Int, slide: SlideScene, widthPx: Int, rect: NormRect): Bitmap? {
        val size = slide.scene.size
        if (size.width <= 0.0 || size.height <= 0.0 || widthPx <= 0) return null
        val left = rect.left.coerceIn(0f, 1f)
        val top = rect.top.coerceIn(0f, 1f)
        val right = rect.right.coerceIn(left, 1f)
        val bottom = rect.bottom.coerceIn(top, 1f)
        if (right <= left || bottom <= top) return null
        val region = RenderRegion(
            source = RectF(
                left * size.width.toFloat(),
                top * size.height.toFloat(),
                right * size.width.toFloat(),
                bottom * size.height.toFloat(),
            ),
            scale = minOf(
                widthPx.toFloat() / ((right - left) * size.width.toFloat()),
                limits.maxRenderEdgePx.toFloat() / ((bottom - top) * size.height.toFloat()),
            ),
        )
        val list = displayListOf(index, slide)
        val decoded = HashMap<String, Bitmap?>()
        return SoftwareCanvas.renderRegion(
            list = list,
            region = region,
            imageOf = { ref ->
                ref.bitmap ?: if (decoded.containsKey(ref.key)) {
                    decoded[ref.key]
                } else {
                    images.load(File(ref.key), region.widthPx)?.bitmap.also { decoded[ref.key] = it }
                }
            },
            fonts = fonts,
        )
    }

    /** One uniform scale that fits both edges, so a tall slide is shrunk rather than cropped. */
    private fun renderSize(size: SceneSize, widthPx: Int): RenderSize {
        val scale = minOf(widthPx.toDouble() / size.width, limits.maxRenderEdgePx.toDouble() / size.height)
        return RenderSize(
            widthPx = (size.width * scale).roundToInt().coerceAtLeast(1),
            heightPx = (size.height * scale).roundToInt().coerceIn(1, limits.maxRenderEdgePx),
            scale = scale.toFloat(),
        )
    }

    private suspend fun displayListOf(index: Int, slide: SlideScene): DisplayList = withContext(Dispatchers.IO) {
        lists.get(index)?.let { return@withContext it }
        val list = renderer.render(slide.scene, resolverOf(slide))
        if (!list.complete && incompleteRenders.add(index)) {
            incompleteFlow.value = true
            Log.w(TAG, "Slide $index render stopped at its work budget")
        }
        lists.put(index, list)
        list
    }

    private fun resolverOf(slide: SlideScene): SlidePictureResolver {
        val pictures = PictureImages(
            pkg = pkg,
            part = slide.part,
            cache = images,
            mediaDir = mediaDir,
            maxEdgePx = limits.maxRenderEdgePx,
            metafiles = metafiles,
            generated = generated,
            labels = placeholderLabels,
        )
        return SlidePictureResolver { picture ->
            pictures.raster(picture.source, picture.frame)?.let { SlideImage(it.file, it.bounds, it.bitmap) }
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
                    textIndexJob?.cancel()
                    runCatching { cache.evictAll() }
                    runCatching { lists.evictAll() }
                    runCatching { scenes.clear() }
                    runCatching { images.clear() }
                    runCatching { synchronized(generated) { generated.clear() } }
                    runCatching { metafiles.trimMemory(evictAll = true) }
                    runCatching { mediaDir.deleteRecursively() }
                }
            }
        }
    }

    private sealed interface CacheKey {
        val index: Int

        data class Page(override val index: Int, val widthPx: Int) : CacheKey

        data class Tile(override val index: Int, val widthPx: Int, val rect: NormRect) : CacheKey
    }

    @Suppress("TooGenericExceptionCaught")
    companion object {
        private const val TAG = "SlidePageSource"

        private const val BYTES_PER_PIXEL = 4

        private const val PAGES_PER_CACHE = 3

        private const val MAX_CACHED_LISTS = 6

        private const val PPTX_DIR_PREFIX = "pptx-"

        /** Only dirs left by an earlier process are stale; a dir created this run may still be in use. */
        private val processStartMillis = System.currentTimeMillis()

        private val openSources: MutableSet<SlidePageSource> =
            Collections.newSetFromMap(WeakHashMap<SlidePageSource, Boolean>())

        fun trimAll(evictAll: Boolean) {
            val sources = synchronized(openSources) { openSources.toList() }
            for (source in sources) source.trimMemory(evictAll)
        }

        /** Media directories are per-open UUIDs; a killed process leaves them behind for the next start. */
        fun sweepStaleTempDirs(cacheDir: File) {
            cacheDir.listFiles { file ->
                file.isDirectory && file.name.startsWith(PPTX_DIR_PREFIX) && file.lastModified() < processStartMillis
            }?.forEach { runCatching { it.deleteRecursively() } }
        }

        suspend fun open(
            context: Context,
            uri: Uri,
            buildTextIndex: Boolean = true,
            labels: PictureLabels = PictureLabels.Default,
        ): SlidePageSource = withContext(Dispatchers.IO) {
            val dir = mediaDir(context)
            try {
                openStream(
                    open = {
                        context.contentResolver.openInputStream(uri)
                            ?: throw FileNotFoundException(uri.toString())
                    },
                    mediaDir = dir,
                    presets = presets(context),
                    assets = context.assets,
                    buildTextIndex = buildTextIndex,
                    labels = labels,
                )
            } catch (error: Exception) {
                dir.deleteRecursively()
                throw error
            }
        }

        suspend fun openFile(
            context: Context,
            file: File,
            buildTextIndex: Boolean = true,
            labels: PictureLabels = PictureLabels.Default,
        ): SlidePageSource = withContext(Dispatchers.IO) {
            val dir = mediaDir(context)
            try {
                openStream(
                    open = { file.inputStream() },
                    mediaDir = dir,
                    presets = presets(context),
                    assets = context.assets,
                    buildTextIndex = buildTextIndex,
                    labels = labels,
                )
            } catch (error: Exception) {
                dir.deleteRecursively()
                throw error
            }
        }

        internal suspend fun openStream(
            open: () -> InputStream,
            mediaDir: File,
            presets: PresetGeometry,
            warps: PresetGeometry? = null,
            limits: PptxLimits = PptxLimits(),
            newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
            assets: android.content.res.AssetManager? = null,
            buildTextIndex: Boolean = true,
            labels: PictureLabels = PictureLabels.Default,
        ): SlidePageSource = withContext(Dispatchers.IO) {
            mediaDir.mkdirs()
            val pkg = PptxPackage.read(open, mediaDir, limits, newParserFactory)
            ensureActive()
            val main = pkg.mainPart
            val bytes = pkg.part(main) ?: throw PptxFormatException("$main is missing")
            val presentation = PresentationParser(newParserFactory, limits).parse(bytes, main)
            ensureActive()
            val scenes = StyleResolver(newParserFactory, limits).lazyScenes(pkg, presentation)
            val resolver = TextStyleResolver(limits)
            val embedded = pkg.fontNames.mapNotNull { name -> pkg.font(name)?.let { EmbeddedFontDecoder.decode(name, it) } }
            ensureActive()
            val fonts = assets?.let { FontCatalog(it, mediaDir) }
            val warpSource = warps ?: assets?.let {
                PresetGeometry(open = { it.open("ooxml/presetTextWarpDefinitions.xml") })
            }
            val substitution = FontSubstitution(embedded = embedded)
            val engine = fonts?.let { fontCatalog ->
                TextLayoutEngine(
                    measurer = PaintTextMeasurer(fontCatalog),
                    shaper = TextShaper(FontSlots(substitution)),
                    warps = warpSource?.let(::TextWarpRenderer),
                )
            }
            ensureActive()
            scenes.scene(0)
            ensureActive()
            val source = SlidePageSource(
                pkg = pkg,
                scenes = scenes,
                presentationInfo = presentation,
                mediaDir = mediaDir,
                limits = limits,
                renderer = SlideRenderer(presets, limits, text = engine?.let { SlideTextPainter(resolver, it) }),
                fonts = fonts,
                fontSubstitution = substitution,
                placeholderLabels = labels,
            )
            val build: (check: () -> Unit) -> SlideTextIndex = { check ->
                recover(SlideTextIndex(emptyList())) {
                    val built = if (engine != null) {
                        SlideTextIndex.fromDeck(
                            pkg,
                            presentation,
                            scenes.count,
                            { index -> scenes.scene(index) },
                            resolver,
                            engine,
                            check,
                            limits,
                        )
                    } else SlideTextIndex(emptyList())
                    if (built.pages.isEmpty() && scenes.count > 0) {
                        check()
                        SlideTextIndex.fromTexts(SlideTextExtractor(newParserFactory, limits).extract(open))
                    } else built
                }
            }
            if (buildTextIndex) source.setTextIndex(build { ensureActive() }) else source.startTextIndexBuild(build)
            source.also { synchronized(openSources) { openSources.add(it) } }
        }

        private fun mediaDir(context: Context): File =
            File(context.cacheDir, "$PPTX_DIR_PREFIX${UUID.randomUUID()}")

        private fun presets(context: Context): PresetGeometry =
            PresetGeometry(open = { context.assets.open("ooxml/presetShapeDefinitions.xml") })
    }
}

internal fun CoroutineScope.launchTextIndexBuild(
    build: (check: () -> Unit) -> SlideTextIndex,
    onBuilt: (SlideTextIndex) -> Unit,
): Job = launch {
    val scope = this
    onBuilt(build { scope.ensureActive() })
}

private data class RenderSize(val widthPx: Int, val heightPx: Int, val scale: Float)

internal fun slideAspectRatio(size: SceneSize): Float =
    if (size.width > 0.0) (size.height / size.width).toFloat() else 0f

internal fun slideHeightPx(widthPx: Int, size: SceneSize, maxEdgePx: Int): Int {
    if (size.width <= 0.0 || size.height <= 0.0 || widthPx <= 0) return 1
    val height = widthPx.toDouble() * size.height / size.width
    return if (height.isFinite()) height.roundToInt().coerceIn(1, maxEdgePx) else 1
}
