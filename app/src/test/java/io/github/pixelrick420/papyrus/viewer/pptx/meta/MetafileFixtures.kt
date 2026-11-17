package io.github.pixelrick420.papyrus.viewer.pptx.meta

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object MetafileFixtures {

    fun emf(
        bounds: IntArray = intArrayOf(0, 0, 100, 80),
        frame: IntArray = intArrayOf(0, 0, 1_000, 800),
        records: List<ByteArray> = emptyList(),
        signature: Long = 0x464D4520L,
    ): ByteArray {
        val size = 88 + records.sumOf { it.size }
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1)
        buffer.putInt(88)
        bounds.forEach { buffer.putInt(it) }
        frame.forEach { buffer.putInt(it) }
        buffer.putInt(signature.toInt())
        buffer.putInt(0x0001_0000)
        buffer.putInt(size)
        buffer.putInt(records.size)
        buffer.putShort(1)
        buffer.putShort(0)
        repeat(3) { buffer.putInt(0) }
        buffer.putInt(100)
        buffer.putInt(100)
        buffer.putInt(254)
        buffer.putInt(254)
        records.forEach { buffer.put(it) }
        return buffer.array()
    }

    fun record(type: Int, vararg words: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8 + words.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(type)
        buffer.putInt(buffer.capacity())
        words.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    fun floats(type: Int, values: FloatArray, trailing: Int? = null): ByteArray {
        val buffer = ByteBuffer.allocate(8 + values.size * 4 + if (trailing == null) 0 else 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(type)
        buffer.putInt(buffer.capacity())
        values.forEach { buffer.putFloat(it) }
        trailing?.let { buffer.putInt(it) }
        return buffer.array()
    }

    fun words(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    fun poly16(type: Int, bounds: IntArray, vararg points: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 16 + 4 + points.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(type)
        buffer.putInt(buffer.capacity())
        bounds.forEach { buffer.putInt(it) }
        buffer.putInt(points.size / 2)
        points.forEach { buffer.putShort(it.toShort()) }
        return buffer.array()
    }

    fun dibInfo(width: Int, height: Int, depth: Int = 24, compression: Int = 0): ByteArray {
        val stride = ((width * depth + 31) / 32) * 4
        val buffer = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(40)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putShort(1)
        buffer.putShort(depth.toShort())
        buffer.putInt(compression)
        buffer.putInt(stride * kotlin.math.abs(height))
        repeat(4) { buffer.putInt(0) }
        return buffer.array()
    }

    fun setDibits(bounds: IntArray, info: ByteArray, bits: ByteArray, stretch: Boolean = false): ByteArray {
        val headerBytes = if (stretch) 56 else 48
        val buffer = ByteBuffer.allocate(8 + headerBytes + info.size + bits.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(if (stretch) 0x51 else 0x50)
        buffer.putInt(buffer.capacity())
        bounds.forEach { buffer.putInt(it) }
        if (stretch) repeat(4) { buffer.putInt(0) }
        if (!stretch) {
            buffer.putInt(0)
            buffer.putInt(kotlin.math.abs(bounds[3] - bounds[1]))
        }
        buffer.putInt(8 + headerBytes)
        buffer.putInt(info.size)
        buffer.putInt(8 + headerBytes + info.size)
        buffer.putInt(bits.size)
        buffer.putInt(0)
        buffer.putInt(0x00C8)
        buffer.put(info)
        buffer.put(bits)
        return buffer.array()
    }

    fun wmfRecord(type: Int, parameters: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(6 + parameters.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt((6 + parameters.size) / 2)
        buffer.putShort(type.toShort())
        buffer.put(parameters)
        return buffer.array()
    }
}
