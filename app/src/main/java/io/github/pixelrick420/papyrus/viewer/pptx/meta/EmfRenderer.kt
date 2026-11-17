package io.github.pixelrick420.papyrus.viewer.pptx.meta

import io.github.pixelrick420.papyrus.viewer.pptx.Affine

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
        val drawing = EmfDrawing(page)

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

private class EmfDrawing(page: MetafilePage) : GdiRenderer(page) {

    var finished: Boolean = false
        private set

    fun record(type: Int, reader: GdiReader) {
        when (type) {
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
            EOF -> finished = true
            else -> Unit
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
        const val SET_WINDOW_EXT = 0x09
        const val SET_WINDOW_ORG = 0x0A
        const val SET_VIEWPORT_EXT = 0x0B
        const val SET_VIEWPORT_ORG = 0x0C
        const val EOF = 0x0E
        const val SCALE_VIEWPORT_EXT = 0x1F
        const val SCALE_WINDOW_EXT = 0x20
        const val SAVE_DC = 0x21
        const val RESTORE_DC = 0x22
        const val SET_WORLD_TRANSFORM = 0x23
        const val MODIFY_WORLD_TRANSFORM = 0x24
        const val MWT_IDENTITY = 1
        const val MWT_LEFT_MULTIPLY = 2
        const val MWT_RIGHT_MULTIPLY = 3
    }
}
