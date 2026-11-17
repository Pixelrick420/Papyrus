package io.github.pixelrick420.papyrus.viewer.pptx.meta

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.render.ImageEffects
import io.github.pixelrick420.papyrus.viewer.pptx.render.ImageRef
import io.github.pixelrick420.papyrus.viewer.pptx.render.PathData
import io.github.pixelrick420.papyrus.viewer.pptx.render.PathSegment
import io.github.pixelrick420.papyrus.viewer.pptx.render.PointF
import io.github.pixelrick420.papyrus.viewer.pptx.render.RectF

internal class EmfRenderer(
    private val bytes: ByteArray,
    private val maxEdgePx: Int,
    private val maxPixels: Long,
) {

    fun render(): MetafileImage {
        val reader = GdiReader(bytes)
        if (reader.u32() != EMR_HEADER.toLong()) throw GdiFormatException()
        val headerSize = reader.u32().toInt()
        if (headerSize < HEADER_BYTES || headerSize > bytes.size) throw GdiFormatException()
        val header = GdiReader(bytes, 0, headerSize)
        header.seek(8)
        val bounds = intArrayOf(header.i32(), header.i32(), header.i32(), header.i32())
        val frame = intArrayOf(header.i32(), header.i32(), header.i32(), header.i32())
        header.seek(40)
        if (header.u32() != SIGNATURE || header.u32() != VERSION) throw GdiFormatException()

        val deviceLeft = minOf(bounds[0], bounds[2])
        val deviceTop = minOf(bounds[1], bounds[3])
        val deviceWidth = maxOf(1, kotlin.math.abs(bounds[2] - bounds[0]))
        val deviceHeight = maxOf(1, kotlin.math.abs(bounds[3] - bounds[1]))
        val frameWidth = frame[2] - frame[0]
        val frameHeight = frame[3] - frame[1]
        val sizeUnits = if (frameWidth != 0 && frameHeight != 0) {
            frameWidth to frameHeight
        } else {
            deviceWidth to deviceHeight
        }
        val (width, height) = metafileSize(sizeUnits.first, sizeUnits.second, maxEdgePx, maxPixels)
        val mapping = if (frameWidth == 0 || frameHeight == 0) {
            GdiMapping(
                windowExtX = deviceWidth,
                windowExtY = deviceHeight,
                viewportExtX = deviceWidth,
                viewportExtY = deviceHeight,
            )
        } else {
            GdiMapping(
                windowExtX = frameWidth,
                windowExtY = frameHeight,
                windowOrgX = frame[0],
                windowOrgY = frame[1],
                viewportExtX = deviceWidth,
                viewportExtY = deviceHeight,
                viewportOrgX = deviceLeft,
                viewportOrgY = deviceTop,
            )
        }
        val page = MetafilePage(width, height, deviceLeft, deviceTop, deviceWidth, deviceHeight, mapping)
        val drawing = EmfDrawing(page, maxEdgePx, maxPixels)

        reader.seek(headerSize)
        while (reader.remaining >= RECORD_HEADER_BYTES) {
            val type = reader.u32().toInt()
            val size = reader.u32().toInt()
            if (size < RECORD_HEADER_BYTES || size > reader.remaining + RECORD_HEADER_BYTES) break
            val body = reader.sub(size - RECORD_HEADER_BYTES)
            try {
                drawing.record(type, body)
            } catch (_: GdiFormatException) {
                continue
            }
            if (drawing.finished) break
        }
        return drawing.result()
    }

    private companion object {
        const val EMR_HEADER = 0x01
        const val RECORD_HEADER_BYTES = 8
        const val HEADER_BYTES = 88
        const val SIGNATURE = 0x464D4520L
        const val VERSION = 0x00010000L
    }
}

private class EmfDrawing(
    page: MetafilePage,
    private val maxEdgePx: Int,
    private val maxPixels: Long,
) : GdiRenderer(page) {

    var finished: Boolean = false
        private set

    private var dibCount = 0

    fun record(type: Int, reader: GdiReader) {
        when (type) {
            POLYBEZIER -> bezier(readPoints(reader, POINT_BYTES), fromCurrent = false)
            POLYGON -> polyline(readPoints(reader, POINT_BYTES), close = true)
            POLYLINE -> polyline(readPoints(reader, POINT_BYTES), close = false)
            POLYBEZIERTO -> bezier(readPoints(reader, POINT_BYTES), fromCurrent = true)
            POLYLINETO -> polyline(readPoints(reader, POINT_BYTES), close = false)
            POLYPOLYLINE -> readPolygons(reader, POINT_BYTES, close = false)
            POLYPOLYGON -> readPolygons(reader, POINT_BYTES, close = true)
            SET_WINDOW_EXT -> {
                val cx = reader.i32()
                val cy = reader.i32()
                state = state.copy(mapping = state.mapping.copy(windowExtX = cx, windowExtY = cy))
            }
            SET_WINDOW_ORG -> {
                val x = reader.i32()
                val y = reader.i32()
                state = state.copy(mapping = state.mapping.copy(windowOrgX = x, windowOrgY = y))
            }
            SET_VIEWPORT_EXT -> {
                val cx = reader.i32()
                val cy = reader.i32()
                state = state.copy(mapping = state.mapping.copy(viewportExtX = cx, viewportExtY = cy))
            }
            SET_VIEWPORT_ORG -> {
                val x = reader.i32()
                val y = reader.i32()
                state = state.copy(mapping = state.mapping.copy(viewportOrgX = x, viewportOrgY = y))
            }
            SET_BKMODE -> state = state.copy(opaqueText = reader.i32() != 0)
            SET_TEXTALIGN -> state = state.copy(textAlign = reader.i32())
            SET_TEXTCOLOR -> state = state.copy(textColor = colorRef(reader.i32()))
            SET_BKCOLOR -> state = state.copy(backgroundColor = colorRef(reader.i32()))
            SET_POLYFILLMODE -> state = state.copy(polyFillMode = reader.i32())
            SCALE_VIEWPORT_EXT -> scaleExtent(reader, viewport = true)
            SCALE_WINDOW_EXT -> scaleExtent(reader, viewport = false)
            SAVE_DC -> save()
            RESTORE_DC -> restore(reader.i32())
            SET_WORLD_TRANSFORM -> setWorld(reader.fixed(), reader.fixed(), reader.fixed(), reader.fixed(), reader.fixed(), reader.fixed())
            MODIFY_WORLD_TRANSFORM -> {
                val matrix = Affine(reader.f32().toDouble(), reader.f32().toDouble(), reader.f32().toDouble(), reader.f32().toDouble(), reader.f32().toDouble(), reader.f32().toDouble())
                when (val mode = reader.u32().toInt()) {
                    MWT_IDENTITY -> state = state.copy(world = Affine.IDENTITY)
                    MWT_LEFT_MULTIPLY -> state = state.copy(world = state.world.then(matrix))
                    MWT_RIGHT_MULTIPLY -> state = state.copy(world = matrix.then(state.world))
                    else -> Unit
                }
            }
            SELECT_OBJECT -> selectObject(reader.i32())
            CREATE_PEN -> createPen(reader)
            CREATE_BRUSH_INDIRECT -> createBrush(reader)
            DELETE_OBJECT -> deleteObject(reader.i32())
            ELLIPSE -> shape(reader, ellipse = true)
            RECTANGLE -> shape(reader, ellipse = false)
            ROUND_RECT -> roundRect(reader)
            MOVETOEX -> position = point(reader.i32(), reader.i32())
            LINETO -> lineTo(reader)
            SET_DIBITS_TO_DEVICE -> setDibitsToDevice(reader)
            STRETCH_DIBITS -> stretchDibits(reader)
            EXT_CREATE_PEN -> createExtendedPen(reader)
            EXT_CREATE_FONT_W -> createFont(reader)
            EXT_TEXT_OUT_W -> textOut(reader)
            POLYBEZIER16 -> bezier(readPoints(reader, SHORT_POINT_BYTES), fromCurrent = false)
            POLYGON16 -> polyline(readPoints(reader, SHORT_POINT_BYTES), close = true)
            POLYLINE16 -> polyline(readPoints(reader, SHORT_POINT_BYTES), close = false)
            POLYBEZIERTO16 -> bezier(readPoints(reader, SHORT_POINT_BYTES), fromCurrent = true)
            POLYLINETO16 -> polyline(readPoints(reader, SHORT_POINT_BYTES), close = false)
            POLYPOLYLINE16 -> readPolygons(reader, SHORT_POINT_BYTES, close = false)
            POLYPOLYGON16 -> readPolygons(reader, SHORT_POINT_BYTES, close = true)
            EOF -> finished = true
            else -> Unit
        }
    }

    private fun createPen(reader: GdiReader) {
        val style = reader.i32()
        val width = reader.i32()
        reader.i32()
        val color = colorRef(reader.i32())
        addObject(reader.i32(), GdiObject.Pen(GdiPen(style, width.toFloat(), color)))
    }

    private fun createBrush(reader: GdiReader) {
        val style = reader.i32()
        val color = colorRef(reader.i32())
        val hatch = reader.i32()
        val brush = if (style == BS_NULL) null else GdiBrush(style, color, hatch)
        addObject(reader.i32(), GdiObject.Brush(brush))
    }

    private fun createExtendedPen(reader: GdiReader) {
        val offset = reader.i32()
        val penStart = reader.start - RECORD_HEADER_BYTES + offset
        if (penStart < reader.start || penStart + 16 > reader.end) throw GdiFormatException()
        val pen = GdiReader(reader.bytes, penStart)
        val style = pen.u32().toInt()
        val width = pen.i32()
        pen.u32()
        val color = colorRef(pen.i32())
        val handle = GdiReader(reader.bytes, reader.end - 4).i32()
        addObject(handle, GdiObject.Pen(GdiPen(style, width.toFloat(), color)))
    }

    private fun createFont(reader: GdiReader) {
        val offset = reader.i32()
        val fontStart = reader.start - RECORD_HEADER_BYTES + offset
        if (fontStart < reader.start || fontStart + LOGFONT_BYTES > reader.end) throw GdiFormatException()
        val font = GdiReader(reader.bytes, fontStart)
        val height = font.i32()
        repeat(3) { font.i32() }
        val weight = font.i32()
        val italic = font.u8() != 0
        val underline = font.u8() != 0
        val strikeOut = font.u8() != 0
        repeat(5) { font.u8() }
        val name = font.utf16le(FACE_NAME_CHARS).trim('\u0000')
        val handle = GdiReader(reader.bytes, reader.end - 4).i32()
        addObject(handle, GdiObject.Font(GdiFont(height, name, weight, italic, underline, strikeOut)))
    }

    private fun textOut(reader: GdiReader) {
        val recordStart = reader.start - RECORD_HEADER_BYTES
        repeat(4) { reader.i32() }
        reader.i32()
        val x = reader.i32()
        val y = reader.i32()
        val count = reader.i32()
        val stringOffset = reader.i32()
        reader.i32()
        reader.i32()
        if (count <= 0 || count > MAX_TEXT_CHARS) return
        val start = recordStart + stringOffset
        if (start < reader.start || start + count * 2 > reader.end) throw GdiFormatException()
        val text = GdiReader(reader.bytes, start).utf16le(count).trim('\u0000')
        val at = point(x, y)
        drawText(text, at.x, at.y)
    }

    private fun shape(reader: GdiReader, ellipse: Boolean) {
        val bounds = rect(reader.i32(), reader.i32(), reader.i32(), reader.i32())
        val path = if (ellipse) ellipsePath(bounds) else PathData.rect(bounds)
        outlineAndFill(path)
    }

    private fun roundRect(reader: GdiReader) {
        val bounds = rect(reader.i32(), reader.i32(), reader.i32(), reader.i32())
        val corner = vector(reader.i32(), reader.i32())
        outlineAndFill(roundRectPath(bounds, corner))
    }

    private fun lineTo(reader: GdiReader) {
        val end = point(reader.i32(), reader.i32())
        val path = PathData(listOf(PathSegment.MoveTo(position.x, position.y), PathSegment.LineTo(end.x, end.y)))
        stroke(path, state.pen)
        position = end
    }

    private fun setDibitsToDevice(reader: GdiReader) {
        val destination = rect(reader.i32(), reader.i32(), reader.i32(), reader.i32())
        reader.i32()
        reader.i32()
        val offsetBitmapInfo = reader.i32()
        reader.i32()
        val offsetBits = reader.i32()
        reader.i32()
        reader.i32()
        reader.i32()
        drawDib(reader, offsetBitmapInfo, offsetBits, destination)
    }

    private fun stretchDibits(reader: GdiReader) {
        val destination = rect(reader.i32(), reader.i32(), reader.i32(), reader.i32())
        repeat(4) { reader.i32() }
        val offsetBitmapInfo = reader.i32()
        reader.i32()
        val offsetBits = reader.i32()
        reader.i32()
        reader.i32()
        reader.i32()
        drawDib(reader, offsetBitmapInfo, offsetBits, destination)
    }

    private fun drawDib(reader: GdiReader, offsetBitmapInfo: Int, offsetBits: Int, destination: RectF) {
        val base = reader.start - RECORD_HEADER_BYTES
        val pixels = MetafileDib.decode(
            reader.bytes,
            base + offsetBitmapInfo,
            base + offsetBits,
            maxEdgePx,
            maxPixels,
        ) ?: return
        val key = "emf-dib-${dibCount++}"
        dibs[key] = pixels
        target.drawImage(
            ImageRef(key, pixels.width, pixels.height),
            RectF(0f, 0f, pixels.width.toFloat(), pixels.height.toFloat()),
            destination,
            ImageEffects(),
        )
    }

    private fun readPoints(reader: GdiReader, pointBytes: Int): List<PointF> {
        repeat(4) { reader.i32() }
        val count = reader.i32()
        if (count <= 0 || count > MAX_POINTS) throw GdiFormatException()
        val hasRange = reader.remaining >= count * pointBytes + 8
        val first = if (hasRange) reader.i32() else 0
        val last = if (hasRange) reader.i32() else count
        val points = (0 until count).map {
            if (pointBytes == POINT_BYTES) point(reader.i32(), reader.i32()) else point(reader.i16(), reader.i16())
        }
        if (first !in 0..count || last !in first..count) return points
        return points.subList(first, last)
    }

    private fun readPolygons(reader: GdiReader, pointBytes: Int, close: Boolean) {
        repeat(4) { reader.i32() }
        val count = reader.i32()
        if (count <= 0 || count > MAX_POLYGONS) throw GdiFormatException()
        val lengths = (0 until count).map { reader.u32().toInt() }
        if (lengths.any { it <= 0 } || lengths.sum() > MAX_POINTS) throw GdiFormatException()
        lengths.forEach { length ->
            val points = (0 until length).map {
                if (pointBytes == POINT_BYTES) point(reader.i32(), reader.i32()) else point(reader.i16(), reader.i16())
            }
            polyline(points, close)
        }
    }

    private fun scaleExtent(reader: GdiReader, viewport: Boolean) {
        val numeratorX = reader.i32()
        val denominatorX = reader.i32()
        val numeratorY = reader.i32()
        val denominatorY = reader.i32()
        if (denominatorX == 0 || denominatorY == 0) return
        val mapping = state.mapping
        state = state.copy(
            mapping = if (viewport) {
                mapping.copy(
                    viewportExtX = (mapping.viewportExtX.toLong() * numeratorX / denominatorX).toInt(),
                    viewportExtY = (mapping.viewportExtY.toLong() * numeratorY / denominatorY).toInt(),
                )
            } else {
                mapping.copy(
                    windowExtX = (mapping.windowExtX.toLong() * numeratorX / denominatorX).toInt(),
                    windowExtY = (mapping.windowExtY.toLong() * numeratorY / denominatorY).toInt(),
                )
            },
        )
    }

    private fun setWorld(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        state = state.copy(world = Affine(a, b, c, d, e, f))
    }

    private fun GdiReader.fixed(): Double = i32() / 65536.0

    private companion object {
        const val POLYBEZIER = 0x02
        const val POLYGON = 0x03
        const val POLYLINE = 0x04
        const val POLYBEZIERTO = 0x05
        const val POLYLINETO = 0x06
        const val POLYPOLYLINE = 0x07
        const val POLYPOLYGON = 0x08
        const val SET_WINDOW_EXT = 0x09
        const val SET_WINDOW_ORG = 0x0A
        const val SET_VIEWPORT_EXT = 0x0B
        const val SET_VIEWPORT_ORG = 0x0C
        const val EOF = 0x0E
        const val SET_BKMODE = 0x12
        const val SET_POLYFILLMODE = 0x13
        const val SET_TEXTALIGN = 0x16
        const val SET_TEXTCOLOR = 0x18
        const val SET_BKCOLOR = 0x19
        const val MOVETOEX = 0x1B
        const val SCALE_VIEWPORT_EXT = 0x1F
        const val SCALE_WINDOW_EXT = 0x20
        const val SAVE_DC = 0x21
        const val RESTORE_DC = 0x22
        const val SET_WORLD_TRANSFORM = 0x23
        const val MODIFY_WORLD_TRANSFORM = 0x24
        const val SELECT_OBJECT = 0x25
        const val CREATE_PEN = 0x26
        const val CREATE_BRUSH_INDIRECT = 0x27
        const val DELETE_OBJECT = 0x28
        const val ELLIPSE = 0x2A
        const val RECTANGLE = 0x2B
        const val ROUND_RECT = 0x2C
        const val LINETO = 0x36
        const val SET_DIBITS_TO_DEVICE = 0x50
        const val STRETCH_DIBITS = 0x51
        const val EXT_CREATE_FONT_W = 0x52
        const val EXT_TEXT_OUT_W = 0x54
        const val EXT_CREATE_PEN = 0x5F
        const val POLYBEZIER16 = 0x55
        const val POLYGON16 = 0x56
        const val POLYLINE16 = 0x57
        const val POLYBEZIERTO16 = 0x58
        const val POLYLINETO16 = 0x59
        const val POLYPOLYLINE16 = 0x5A
        const val POLYPOLYGON16 = 0x5B
        const val MWT_IDENTITY = 1
        const val MWT_LEFT_MULTIPLY = 2
        const val MWT_RIGHT_MULTIPLY = 3
        const val BS_NULL = 1
        const val RECORD_HEADER_BYTES = 8
        const val POINT_BYTES = 8
        const val SHORT_POINT_BYTES = 4
        const val MAX_POINTS = 65_536
        const val MAX_POLYGONS = 4_096
        const val MAX_TEXT_CHARS = 16_384
        const val LOGFONT_BYTES = 92
        const val FACE_NAME_CHARS = 32
    }
}
