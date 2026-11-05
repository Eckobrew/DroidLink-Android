package com.droiddock.companion.mirroring

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One H.264 access unit (or codec configuration), inside the authenticated media channel. */
data class VideoPacket(val kind: Int, val presentationTimeUs: Long, val payload: ByteArray) {
    fun encode(): ByteArray {
        require(kind in 0..2 && presentationTimeUs >= 0)
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD)
        return ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte()).put(kind.toByte()).putLong(presentationTimeUs)
            .putInt(payload.size).put(payload).array()
    }

    companion object {
        const val VERSION = 1
        const val HEADER_SIZE = 14
        const val MAX_PAYLOAD = 8 * 1024 * 1024
        const val FRAME = 0
        const val KEY_FRAME = 1
        const val CONFIG = 2
    }
}
