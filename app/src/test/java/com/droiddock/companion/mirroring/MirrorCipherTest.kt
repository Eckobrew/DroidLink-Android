package com.droiddock.companion.mirroring

import org.junit.Assert.*
import org.junit.Test

class MirrorCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val id = "00000000-0000-0000-0000-000000000001"
    @Test fun matchesSwiftAndRejectsReplay() {
        val phone = MirrorCipher(key, id)
        val mac = MirrorCipher(key, id, isMac = true)
        val record = phone.seal(byteArrayOf(6))
        assertEquals("0000000000000001ad8f9d970d9226c138724a721fec8a0711", record.joinToString("") { "%02x".format(it) })
        assertArrayEquals(byteArrayOf(6), mac.open(record))
        assertThrows(IllegalArgumentException::class.java) { mac.open(record) }
        assertArrayEquals(byteArrayOf(3, 42), phone.open(mac.seal(byteArrayOf(3, 42))))
    }
    @Test fun rejectsTamperingWithoutAdvancingSequence() {
        val phone = MirrorCipher(key, id)
        val mac = MirrorCipher(key, id, true)
        val record = phone.seal(byteArrayOf(1, 2, 3))
        val bad = record.clone(); bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { mac.open(bad) }
        assertArrayEquals(byteArrayOf(1, 2, 3), mac.open(record))
        assertThrows(Exception::class.java) { MirrorCipher(key, "00000000-0000-0000-0000-000000000002", true).open(record) }
        assertThrows(Exception::class.java) { MirrorCipher(key, id).open(record) }
    }
}
