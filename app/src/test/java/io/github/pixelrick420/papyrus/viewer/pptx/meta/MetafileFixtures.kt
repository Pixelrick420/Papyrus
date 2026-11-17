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

    fun emfFont(handle: Int = 3, height: Int = 120, name: String = "Arial", underline: Boolean = false): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 4 + 92 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x52)
        buffer.putInt(buffer.capacity())
        buffer.putInt(12)
        buffer.putInt(height)
        repeat(3) { buffer.putInt(0) }
        buffer.putInt(400)
        buffer.put(0.toByte())
        buffer.put(if (underline) 1.toByte() else 0.toByte())
        repeat(6) { buffer.put(0.toByte()) }
        repeat(32) { index -> buffer.putShort(if (index < name.length) name[index].code.toShort() else 0) }
        buffer.putInt(handle)
        return buffer.array()
    }

    fun emfTextOut(text: String, x: Int, y: Int): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 44 + text.length * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x54)
        buffer.putInt(buffer.capacity())
        repeat(4) { buffer.putInt(0) }
        buffer.putInt(0)
        buffer.putInt(x)
        buffer.putInt(y)
        buffer.putInt(text.length)
        buffer.putInt(52)
        buffer.putInt(0)
        buffer.putInt(0)
        text.forEach { buffer.putShort(it.code.toShort()) }
        return buffer.array()
    }

    fun wmfRecord(type: Int, parameters: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(4 + parameters.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(((4 + parameters.size) / 2).toShort())
        buffer.putShort(type.toShort())
        buffer.put(parameters)
        return buffer.array()
    }

    fun wmf(
        records: List<ByteArray>,
        bounds: IntArray = intArrayOf(0, 0, 1_000, 800),
        placeable: Boolean = true,
    ): ByteArray {
        val header = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
        header.putShort(1)
        header.putShort(9)
        header.putShort(0x0300)
        header.putInt((18 + records.sumOf { it.size }) / 2)
        header.putShort(0)
        header.putInt(records.maxOfOrNull { it.size / 2 } ?: 0)
        header.putShort(records.size.toShort())
        val prefix = if (placeable) {
            val buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
            buffer.putInt(0x9AC6CDD7.toInt())
            buffer.putShort(0)
            bounds.forEach { buffer.putShort(it.toShort()) }
            buffer.putShort(1440)
            buffer.putInt(0)
            buffer.putShort(0)
            buffer.array()
        } else {
            byteArrayOf()
        }
        return prefix + header.array() + records.fold(byteArrayOf()) { acc, record -> acc + record }
    }
}
