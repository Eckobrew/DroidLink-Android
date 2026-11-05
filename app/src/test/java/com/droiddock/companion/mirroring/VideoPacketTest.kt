package com.droiddock.companion.mirroring

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPacketTest {
    @Test fun matchesSwiftWireVector() {
        val packet = VideoPacket(VideoPacket.KEY_FRAME, 1234, byteArrayOf(0, 0, 0, 1, 0x65))
        assertEquals("010100000000000004d2000000050000000165", packet.encode().joinToString("") { "%02x".format(it) })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizePayload() {
        VideoPacket(VideoPacket.FRAME, 0, ByteArray(VideoPacket.MAX_PAYLOAD + 1)).encode()
    }
}
