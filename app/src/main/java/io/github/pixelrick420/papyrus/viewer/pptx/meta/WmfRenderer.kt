package io.github.pixelrick420.papyrus.viewer.pptx.meta

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.render.PathData
import io.github.pixelrick420.papyrus.viewer.pptx.render.PathSegment
import io.github.pixelrick420.papyrus.viewer.pptx.render.PointF
import io.github.pixelrick420.papyrus.viewer.pptx.render.RectF
import kotlin.math.abs
import kotlin.math.max

internal class WmfRenderer(
    private val bytes: ByteArray,
    private val maxEdgePx: Int,
    private val maxPixels: Long,
) {

    fun render(): MetafileImage {
        val header = GdiReader(bytes)
        val placeable = if (header.remaining >= PLACEABLE_BYTES && GdiReader(bytes, 0, 4).u32() == PLACEABLE_KEY) {
            val reader = GdiReader(bytes, 0, PLACEABLE_BYTES)
            reader.u32()
            reader.i16()
            Placeable(reader.i16(), reader.i16(), reader.i16(), reader.i16())
        } else {
            null
        }
        val meta = GdiReader(bytes, placeable?.let { PLACEABLE_BYTES } ?: 0)
        meta.u16()
        val headerWords = meta.u16()
        meta.u16()
        meta.u32()
        meta.u16()
        meta.u32()
        meta.u16()
        val recordsStart = (placeable?.let { PLACEABLE_BYTES } ?: 0) + headerWords * 2
        if (headerWords < META_HEADER_WORDS || recordsStart > bytes.size) throw GdiFormatException()

        val window = windowExtent(recordsStart, placeable)
        val deviceLeft = placeable?.left ?: 0
        val deviceTop = placeable?.top ?: 0
        val deviceWidth = max(1, placeable?.let { abs(it.right - it.left) } ?: window.first)
        val deviceHeight = max(1, placeable?.let { abs(it.bottom - it.top) } ?: window.second)
        val (width, height) = metafileSize(deviceWidth, deviceHeight, maxEdgePx, maxPixels)
        val mapping = GdiMapping(
            windowExtX = max(1, window.first),
            windowExtY = max(1, window.second),
            windowOrgX = 0,
            windowOrgY = 0,
            viewportExtX = deviceWidth,
            viewportExtY = deviceHeight,
            viewportOrgX = deviceLeft,
            viewportOrgY = deviceTop,
        )
        val page = MetafilePage(width, height, deviceLeft, deviceTop, deviceWidth, deviceHeight, mapping)
        val drawing = WmfDrawing(page)

        val reader = GdiReader(bytes, recordsStart)
        while (reader.remaining >= WORD * 3) {
            val sizeWords = reader.u32().toInt()
            val function = reader.u16()
            if (sizeWords < RECORD_HEADER_WORDS || (sizeWords - RECORD_HEADER_WORDS) * WORD > reader.remaining) break
            val parameters = reader.sub((sizeWords - RECORD_HEADER_WORDS) * WORD)
            try {
                drawing.record(function, parameters)
            } catch (_: GdiFormatException) {
                continue
            }
            if (drawing.finished) break
        }
        return drawing.result()
    }

    private fun windowExtent(recordsStart: Int, placeable: Placeable?): Pair<Int, Int> {
        val fromPlaceable = placeable?.let { abs(it.right - it.left) to abs(it.bottom - it.top) }
        var width = fromPlaceable?.first ?: DEFAULT_EXTENT
        var height = fromPlaceable?.second ?: DEFAULT_EXTENT
        val reader = GdiReader(bytes, recordsStart)
        while (reader.remaining >= WORD * 3) {
            val sizeWords = reader.u32().toInt()
            val function = reader.u16()
            if (sizeWords < RECORD_HEADER_WORDS || (sizeWords - RECORD_HEADER_WORDS) * WORD > reader.remaining) break
            val parameters = reader.sub((sizeWords - RECORD_HEADER_WORDS) * WORD)
            if (function == SET_WINDOW_EXT && parameters.remaining >= 4) {
                val cy = parameters.i16()
                val cx = parameters.i16()
                if (cx != 0 && cy != 0) {
                    width = abs(cx)
                    height = abs(cy)
                }
            }
        }
        return width to height
    }

    private data class Placeable(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private companion object {
        const val PLACEABLE_KEY = 0x9AC6CDD7L
        const val PLACEABLE_BYTES = 22
        const val META_HEADER_WORDS = 9
        const val RECORD_HEADER_WORDS = 3
        const val WORD = 2
        const val DEFAULT_EXTENT = 1_000
        const val SET_WINDOW_EXT = 0x020C
    }
}

private class WmfDrawing(page: MetafilePage) : GdiRenderer(page) {

    var finished: Boolean = false
        private set

    fun record(type: Int, reader: GdiReader) {
        when (type) {
            EOF -> finished = true
            SAVE_DC -> save()
            RESTORE_DC -> restore(reader.i16())
            SET_BK_COLOR -> state = state.copy(backgroundColor = rgb(reader))
            SET_TEXT_COLOR -> state = state.copy(textColor = rgb(reader))
            SET_BK_MODE -> state = state.copy(opaqueText = reader.i16() != 0)
            SET_TEXT_ALIGN -> state = state.copy(textAlign = reader.i16())
            SET_WINDOW_ORG -> {
                val y = reader.i16()
                val x = reader.i16()
                state = state.copy(mapping = state.mapping.copy(windowOrgX = x, windowOrgY = y))
            }
            SET_WINDOW_EXT -> {
                val cy = reader.i16()
                val cx = reader.i16()
                state = state.copy(mapping = state.mapping.copy(windowExtX = cx, windowExtY = cy))
            }
            SET_VIEWPORT_ORG -> {
                val y = reader.i16()
                val x = reader.i16()
                state = state.copy(mapping = state.mapping.copy(viewportOrgX = x, viewportOrgY = y))
            }
            SET_VIEWPORT_EXT -> {
                val cy = reader.i16()
                val cx = reader.i16()
                state = state.copy(mapping = state.mapping.copy(viewportExtX = cx, viewportExtY = cy))
            }
            SELECT_OBJECT -> selectObject(reader.i16())
            DELETE_OBJECT -> deleteObject(reader.i16())
            CREATE_PEN_INDIRECT -> createPen(reader)
            CREATE_BRUSH_INDIRECT -> createBrush(reader)
            CREATE_FONT_INDIRECT -> createFont(reader)
            ELLIPSE -> shape(reader, ellipse = true)
            RECTANGLE -> shape(reader, ellipse = false)
            ROUND_RECT -> roundRect(reader)
            MOVE_TO -> position = point(reader.i16(), reader.i16())
            LINE_TO -> lineTo(reader)
            POLYGON -> polyline(readPoints(reader), close = true)
            POLYLINE -> polyline(readPoints(reader), close = false)
            POLYPOLYGON -> readPolygons(reader)
            TEXT_OUT -> textOut(reader)
            EXT_TEXT_OUT -> extendedTextOut(reader)
            else -> Unit
        }
    }

    private fun rgb(reader: GdiReader): Rgba {
        val red = reader.u8()
        val green = reader.u8()
        val blue = reader.u8()
        return Rgba(red, green, blue)
    }

    private fun createPen(reader: GdiReader) {
        val style = reader.u16()
        val width = reader.i16()
        reader.i16()
        createObject(GdiObject.Pen(GdiPen(style, width.toFloat(), rgb(reader))))
    }

    private fun createBrush(reader: GdiReader) {
        val style = reader.u16()
        val color = rgb(reader)
        val hatch = reader.u16()
        val brush = if (style == BS_NULL) null else GdiBrush(style, color, hatch)
        createObject(GdiObject.Brush(brush))
    }

    private fun createFont(reader: GdiReader) {
        val height = reader.i16()
        reader.i16()
        reader.i16()
        reader.i16()
        val weight = reader.i16()
        val italic = reader.u8() != 0
        val underline = reader.u8() != 0
        val strikeOut = reader.u8() != 0
        repeat(5) { reader.u8() }
        val name = String(reader.bytes(FACE_NAME_BYTES), Charsets.ISO_8859_1).trim('\u0000')
        createObject(GdiObject.Font(GdiFont(height, name, weight, italic, underline, strikeOut)))
    }

    private fun shape(reader: GdiReader, ellipse: Boolean) {
        val bounds = bounds(reader)
        outlineAndFill(if (ellipse) ellipsePath(bounds) else PathData.rect(bounds))
    }

    private fun roundRect(reader: GdiReader) {
        val bounds = bounds(reader)
        val corner = vector(reader.i16(), reader.i16())
        outlineAndFill(roundRectPath(bounds, corner))
    }

    private fun lineTo(reader: GdiReader) {
        val y = reader.i16()
        val x = reader.i16()
        val end = point(x, y)
        val path = PathData(listOf(PathSegment.MoveTo(position.x, position.y), PathSegment.LineTo(end.x, end.y)))
        stroke(path, state.pen)
        position = end
    }

    private fun bounds(reader: GdiReader): RectF {
        val bottom = reader.i16()
        val right = reader.i16()
        val top = reader.i16()
        val left = reader.i16()
        return rect(left, top, right, bottom)
    }

    private fun readPoints(reader: GdiReader): List<PointF> {
        val count = reader.u16()
        if (count == 0 || count > MAX_POINTS) throw GdiFormatException()
        return (0 until count).map { point(reader.i16(), reader.i16()) }
    }

    private fun readPolygons(reader: GdiReader) {
        val count = reader.u16()
        if (count == 0 || count > MAX_POLYGONS) throw GdiFormatException()
        val lengths = (0 until count).map { reader.u16() }
        if (lengths.any { it == 0 } || lengths.sum() > MAX_POINTS) throw GdiFormatException()
        lengths.forEach { length ->
            val points = (0 until length).map { point(reader.i16(), reader.i16()) }
            polyline(points, close = true)
        }
    }

    private fun textOut(reader: GdiReader) {
        val count = reader.u16()
        if (count == 0 || count > MAX_TEXT_CHARS) return
        val text = String(reader.bytes(count + count % 2), Charsets.ISO_8859_1).trim('\u0000')
        val y = reader.i16()
        val x = reader.i16()
        val at = point(x, y)
        drawText(text, at.x, at.y)
    }

    private fun extendedTextOut(reader: GdiReader) {
        val y = reader.i16()
        val x = reader.i16()
        val count = reader.u16()
        val options = reader.u16()
        if (count == 0 || count > MAX_TEXT_CHARS) return
        if (options and ETO_RECTANGLE != 0) repeat(4) { reader.i16() }
        val text = String(reader.bytes(count + count % 2), Charsets.ISO_8859_1).trim('\u0000')
        val at = point(x, y)
        drawText(text, at.x, at.y)
    }

    private companion object {
        const val EOF = 0x0000
        const val SAVE_DC = 0x001E
        const val SET_BK_COLOR = 0x0201
        const val SET_TEXT_COLOR = 0x0209
        const val SET_WINDOW_ORG = 0x020B
        const val SET_WINDOW_EXT = 0x020C
        const val SET_VIEWPORT_ORG = 0x020D
        const val SET_VIEWPORT_EXT = 0x020E
        const val LINE_TO = 0x0213
        const val MOVE_TO = 0x0214
        const val RESTORE_DC = 0x0127
        const val SELECT_OBJECT = 0x012D
        const val DELETE_OBJECT = 0x01F0
        const val SET_BK_MODE = 0x0102
        const val SET_TEXT_ALIGN = 0x0104
        const val CREATE_PEN_INDIRECT = 0x02FA
        const val CREATE_FONT_INDIRECT = 0x02FB
        const val CREATE_BRUSH_INDIRECT = 0x02FC
        const val POLYGON = 0x0324
        const val POLYLINE = 0x0325
        const val ELLIPSE = 0x0418
        const val RECTANGLE = 0x041B
        const val TEXT_OUT = 0x0521
        const val POLYPOLYGON = 0x0538
        const val ROUND_RECT = 0x061C
        const val EXT_TEXT_OUT = 0x0A32
        const val ETO_RECTANGLE = 0x03
        const val BS_NULL = 1
        const val FACE_NAME_BYTES = 32
        const val MAX_POINTS = 65_536
        const val MAX_POLYGONS = 4_096
        const val MAX_TEXT_CHARS = 16_384
    }
}
