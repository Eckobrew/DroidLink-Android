package com.droiddock.companion.network

import org.junit.Assert.*
import org.junit.Test

class BleTransportTest {
    @Test fun framingMatchesSwiftWireVector() {
        assertEquals("00000005000102ff41", BleFrameDecoder.encode(byteArrayOf(0, 1, 2, -1, 65)).joinToString("") { "%02x".format(it) })
    }
    @Test fun largeFramesAcrossAllAttBoundaries() {
        val payload = ByteArray(260_000) { (it % 251).toByte() }
        val frame = BleFrameDecoder.encode(payload)
        for (mtu in listOf(1, 7, 20, 182, 244, 512)) {
            val decoder = BleFrameDecoder()
            val received = mutableListOf<ByteArray>()
            for (offset in frame.indices step mtu) received += decoder.append(frame.copyOfRange(offset, minOf(offset + mtu, frame.size)))
            assertEquals(1, received.size)
            assertArrayEquals(payload, received.single())
        }
    }
    @Test fun coalescedFramesAndSplitHeader() {
        val first = BleFrameDecoder.encode("first".toByteArray())
        val second = BleFrameDecoder.encode("second".toByteArray())
        val decoder = BleFrameDecoder()
        assertTrue(decoder.append(first.copyOfRange(0, 3)).isEmpty())
        val result = decoder.append(first.copyOfRange(3, first.size) + second)
        assertEquals(listOf("first", "second"), result.map { String(it) })
    }
    @Test fun malformedLengthsAndDisconnectReset() {
        for (header in listOf(byteArrayOf(0, 0, 0, 0), byteArrayOf(-1, -1, -1, -1), byteArrayOf(0, -128, 0, 1))) {
            val decoder = BleFrameDecoder()
            assertThrows(IllegalArgumentException::class.java) { decoder.append(header) }
            assertArrayEquals(byteArrayOf(42), decoder.append(BleFrameDecoder.encode(byteArrayOf(42))).single())
        }
        val decoder = BleFrameDecoder()
        decoder.append(byteArrayOf(0, 0, 0, 9, 1))
        decoder.reset()
        assertArrayEquals(byteArrayOf(2, 3), decoder.append(BleFrameDecoder.encode(byteArrayOf(2, 3))).single())
    }
    @Test fun rejectsEmptyAndOversizedFrames() {
        assertThrows(IllegalArgumentException::class.java) { BleFrameDecoder.encode(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { BleFrameDecoder.encode(ByteArray(BleFrameDecoder.MAX_FRAME + 1)) }
    }
    @Test fun failoverAndRecoveryPolicy() {
        assertEquals("wifi", TransportPolicy.choose(true, true))
        assertEquals("bluetooth", TransportPolicy.choose(false, true))
        assertEquals("wifi", TransportPolicy.choose(true, true))
        assertEquals("none", TransportPolicy.choose(false, false))
        assertEquals("none", TransportPolicy.choose(false, true, false))
        assertNotEquals(TransportPolicy.sessionSalt("wifi", "nonce"), TransportPolicy.sessionSalt("bluetooth", "nonce"))
    }
    @Test fun commandRetryIsNotExecutedTwiceAcrossTransports() {
        val ids = EnvelopeDeduplicator(2)
        assertTrue(ids.insert("command"))
        assertFalse(ids.insert("command"))
        assertTrue(ids.insert("second"))
        assertTrue(ids.insert("third"))
        assertTrue(ids.insert("command"))
        ids.reset()
        assertTrue(ids.insert("third"))
    }
}
