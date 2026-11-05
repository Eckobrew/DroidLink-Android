package com.droiddock.companion.network

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** Big-endian length prefix; independent of ATT MTU and notification boundaries. */
class BleFrameDecoder {
    companion object {
        const val MAX_FRAME = 8 * 1024 * 1024
        fun encode(payload: ByteArray): ByteArray {
            require(payload.isNotEmpty() && payload.size <= MAX_FRAME) { "Invalid frame length" }
            return ByteBuffer.allocate(payload.size + 4).putInt(payload.size).put(payload).array()
        }
    }
    private val header = ByteArrayOutputStream(4)
    private var payload = ByteArrayOutputStream()
    private var expected = 0
    fun reset() { header.reset(); payload = ByteArrayOutputStream(); expected = 0 }
    fun append(bytes: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < bytes.size) {
            if (expected == 0) {
                val count = minOf(4 - header.size(), bytes.size - offset)
                header.write(bytes, offset, count)
                offset += count
                if (header.size() < 4) continue
                val size = ByteBuffer.wrap(header.toByteArray()).int
                if (size !in 1..MAX_FRAME) { reset(); throw IllegalArgumentException("Invalid frame length") }
                expected = size
                header.reset()
                payload = ByteArrayOutputStream(size)
            }
            val count = minOf(expected - payload.size(), bytes.size - offset)
            payload.write(bytes, offset, count)
            offset += count
            if (payload.size() == expected) {
                frames.add(payload.toByteArray())
                payload = ByteArrayOutputStream()
                expected = 0
            }
        }
        return frames
    }
}

object TransportPolicy {
    fun choose(wifi: Boolean, bluetooth: Boolean, fallbackEnabled: Boolean = true): String =
        if (wifi) "wifi" else if (bluetooth && fallbackEnabled) "bluetooth" else "none"
    fun sessionSalt(transport: String, nonce: String) = "DroidDock_E2EE_v2:$transport:$nonce"
}
