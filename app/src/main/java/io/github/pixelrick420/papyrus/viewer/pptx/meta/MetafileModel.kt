package io.github.pixelrick420.papyrus.viewer.pptx.meta

import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.ImageBounds
import io.github.pixelrick420.papyrus.viewer.pptx.PictureImage
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCap
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeJoin
import io.github.pixelrick420.papyrus.viewer.pptx.render.AndroidCanvasBackend
import io.github.pixelrick420.papyrus.viewer.pptx.render.DisplayList
import io.github.pixelrick420.papyrus.viewer.pptx.render.ImageRef
import io.github.pixelrick420.papyrus.viewer.pptx.render.PaintSpec
import io.github.pixelrick420.papyrus.viewer.pptx.render.PathData
import io.github.pixelrick420.papyrus.viewer.pptx.render.PointF
import io.github.pixelrick420.papyrus.viewer.pptx.render.RectF
import io.github.pixelrick420.papyrus.viewer.pptx.render.RecordingBackend
import io.github.pixelrick420.papyrus.viewer.pptx.render.StrokeSpec
import android.util.LruCache
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class GdiFormatException : RuntimeException()

internal class GdiReader(val bytes: ByteArray, val start: Int = 0, val end: Int = bytes.size) {

    var position: Int = start
        private set

    val remaining: Int get() = end - position

    fun seek(offset: Int) {
        if (offset < start || offset > end) throw GdiFormatException()
        position = offset
    }

    fun skip(count: Int) = seek(position + count)

    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun i8(): Int {
        need(1)
        return bytes[position++].toInt()
    }

    fun u16(): Int {
        need(2)
        val value = (bytes[position].toInt() and 0xFF) or ((bytes[position + 1].toInt() and 0xFF) shl 8)
        position += 2
        return value
    }

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long {
        need(4)
        var value = 0L
        for (shift in 0 until 32 step 8) value = value or ((bytes[position++].toLong() and 0xFF) shl shift)
        return value
    }

    fun i32(): Int = u32().toInt()

    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())

    fun bytes(count: Int): ByteArray {
        need(count)
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    fun sub(count: Int): GdiReader {
        val from = position
        skip(count)
        return GdiReader(bytes, from, position)
    }

    fun utf16le(count: Int): String {
        need(count * 2)
        val chars = CharArray(count) { index ->
            val low = bytes[position + index * 2].toInt() and 0xFF
            val high = bytes[position + index * 2 + 1].toInt() and 0xFF
            (low or (high shl 8)).toChar()
        }
        position += count * 2
        return String(chars)
    }

    private fun need(count: Int) {
        if (count < 0 || count > remaining) throw GdiFormatException()
    }
}

internal data class GdiMapping(
    val windowExtX: Int = 1,
    val windowExtY: Int = 1,
    val windowOrgX: Int = 0,
    val windowOrgY: Int = 0,
    val viewportExtX: Int = 1,
    val viewportExtY: Int = 1,
    val viewportOrgX: Int = 0,
    val viewportOrgY: Int = 0,
)

internal data class GdiPen(val style: Int, val width: Float, val color: Rgba)

internal data class GdiBrush(val style: Int, val color: Rgba, val hatch: Int = 0)

internal data class GdiFont(
    val height: Int,
    val name: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikeOut: Boolean = false,
)

internal data class GdiState(
    val mapping: GdiMapping,
    val world: Affine = Affine.IDENTITY,
    val pen: GdiPen = GdiPen(0, 1f, Rgba.BLACK),
    val brush: GdiBrush? = GdiBrush(0, Rgba.WHITE),
    val font: GdiFont? = null,
    val textColor: Rgba = Rgba.BLACK,
    val backgroundColor: Rgba = Rgba.WHITE,
    val opaqueText: Boolean = false,
    val textAlign: Int = 0,
)

internal class DibPixels(val width: Int, val height: Int, val argb: IntArray)

internal class MetafileImage(
    val bounds: ImageBounds,
    val displayList: DisplayList,
    val dibs: Map<String, DibPixels> = emptyMap(),
)

internal class MetafilePage(
    val width: Int,
    val height: Int,
    val deviceLeft: Int,
    val deviceTop: Int,
    val deviceWidth: Int,
    val deviceHeight: Int,
    val mapping: GdiMapping,
)

internal fun metafileSize(widthUnits: Int, heightUnits: Int, maxEdgePx: Int, maxPixels: Long): Pair<Int, Int> {
    val aspect = maxOf(1, abs(widthUnits)).toDouble() / maxOf(1, abs(heightUnits))
    var height = sqrt(maxPixels.toDouble() / aspect)
    var width = height * aspect
    if (width > maxEdgePx) {
        width = maxEdgePx.toDouble()
        height = width / aspect
    }
    if (height > maxEdgePx) height = maxEdgePx.toDouble()
    return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
}

internal fun deviceTransform(page: MetafilePage, world: Affine, mapping: GdiMapping): Affine {
    val sx = mapping.viewportExtX.toDouble() / mapping.windowExtX
    val sy = mapping.viewportExtY.toDouble() / mapping.windowExtY
    if (!sx.isFinite() || !sy.isFinite() || sx == 0.0 || sy == 0.0) return Affine.IDENTITY
    val windowToDevice = Affine(
        a = sx,
        b = 0.0,
        c = 0.0,
        d = sy,
        e = mapping.viewportOrgX.toDouble() - mapping.windowOrgX * sx,
        f = mapping.viewportOrgY.toDouble() - mapping.windowOrgY * sy,
    )
    val fromDevice = Affine.translate(-page.deviceLeft.toDouble(), -page.deviceTop.toDouble())
    val toPixels = Affine.scale(page.width.toDouble() / page.deviceWidth, page.height.toDouble() / page.deviceHeight)
    return world.then(windowToDevice).then(fromDevice).then(toPixels)
}

internal abstract class GdiRenderer(
    protected val page: MetafilePage,
) {

    protected val target = RecordingBackend()

    protected val dibs = mutableMapOf<String, DibPixels>()

    protected var state: GdiState = GdiState(mapping = page.mapping)

    protected var position: PointF = PointF(0f, 0f)

    private val saved = ArrayDeque<GdiState>()

    protected fun transform(): Affine = deviceTransform(page, state.world, state.mapping)

    protected fun point(x: Int, y: Int): PointF = point(x.toDouble(), y.toDouble())

    protected fun point(x: Double, y: Double): PointF {
        val mapped = transform().map(x, y)
        return PointF(mapped.x.toFloat(), mapped.y.toFloat())
    }

    protected fun rect(left: Int, top: Int, right: Int, bottom: Int): RectF {
        val first = point(left, top)
        val second = point(right, bottom)
        return RectF(
            min(first.x, second.x),
            min(first.y, second.y),
            max(first.x, second.x),
            max(first.y, second.y),
        )
    }

    protected fun save() {
        saved.addLast(state.copy())
    }

    protected fun restore(depth: Int) {
        repeat(minOf(maxOf(0, depth), saved.size)) { state = saved.removeLast() }
    }

    protected fun fill(path: PathData, color: Rgba) = target.fillPath(path, PaintSpec.Solid(color))

    protected fun stroke(path: PathData, pen: GdiPen) {
        if (pen.style == PS_NULL) return
        val scale = sqrt(abs(transform().let { it.a * it.d - it.b * it.c }))
        val width = ((if (pen.width <= 0f) 1f else abs(pen.width)) * scale).toFloat()
        val dash = when (pen.style) {
            PS_DASH -> listOf(width * 4f, width * 2f)
            PS_DOT -> listOf(width, width * 2f)
            PS_DASHDOT -> listOf(width * 4f, width * 2f, width, width * 2f)
            PS_DASHDOTDOT -> listOf(width * 4f, width, width, width, width, width * 2f)
            else -> emptyList()
        }
        target.strokePath(
            path,
            StrokeSpec(
                paint = PaintSpec.Solid(pen.color),
                width = width.coerceAtLeast(0.25f),
                cap = StrokeCap.FLAT,
                join = StrokeJoin.MITER,
                dash = dash,
            ),
        )
    }

    protected fun outlineAndFill(path: PathData) {
        state.brush?.let { fill(path, it.color) }
        stroke(path, state.pen)
    }

    fun result(): MetafileImage = MetafileImage(ImageBounds(page.width, page.height), DisplayList(target.ops), dibs.toMap())

    protected companion object {
        const val PS_SOLID = 0
        const val PS_DASH = 1
        const val PS_DOT = 2
        const val PS_DASHDOT = 3
        const val PS_DASHDOTDOT = 4
        const val PS_NULL = 5
    }
}

internal object Metafiles {

    fun parse(bytes: ByteArray, extension: String, maxEdgePx: Int, maxPixels: Long): MetafileImage? = try {
        when (extension) {
            "emf" -> EmfRenderer(bytes, maxEdgePx, maxPixels).render()
            else -> null
        }
    } catch (_: GdiFormatException) {
        null
    }
}

internal object MetafileBitmaps {

    fun rasterize(image: MetafileImage): Bitmap? {
        if (image.bounds.pixels < 1) return null
        val bitmap = Bitmap.createBitmap(image.bounds.width, image.bounds.height, Bitmap.Config.ARGB_8888)
        val backend = AndroidCanvasBackend(Canvas(bitmap), imageOf = { ref: ImageRef ->
            image.dibs[ref.key]?.let { dib ->
                Bitmap.createBitmap(dib.width, dib.height, Bitmap.Config.ARGB_8888).apply {
                    setPixels(dib.argb, 0, dib.width, 0, 0, dib.width, dib.height)
                }
            }
        })
        image.displayList.play(backend)
        return bitmap
    }
}

internal interface MetafileDecoder {

    fun bounds(file: File): ImageBounds?

    fun load(file: File, targetWidthPx: Int): PictureImage?
}

internal class AndroidMetafileDecoder(
    private val maxEdgePx: Int = PptxLimits().maxRenderEdgePx,
    private val maxPixels: Long = PptxLimits().maxImagePixels,
) : MetafileDecoder {

    private val cache = LruCache<String, MetafileImage>(MAX_CACHED)

    override fun bounds(file: File): ImageBounds? = image(file)?.bounds

    override fun load(file: File, targetWidthPx: Int): PictureImage? {
        val image = image(file) ?: return null
        return MetafileBitmaps.rasterize(image)?.let { PictureImage(it, image.bounds) }
    }

    private fun image(file: File): MetafileImage? {
        cache.get(file.path)?.let { return it }
        if (!file.isFile || file.length() > MAX_FILE_BYTES) return null
        val parsed = try {
            Metafiles.parse(file.readBytes(), file.extension.lowercase(), maxEdgePx, maxPixels)
        } catch (_: OutOfMemoryError) {
            null
        }
        parsed?.let { cache.put(file.path, it) }
        return parsed
    }

    private companion object {
        const val MAX_CACHED = 64
        const val MAX_FILE_BYTES = 32L * 1024 * 1024
    }
}
