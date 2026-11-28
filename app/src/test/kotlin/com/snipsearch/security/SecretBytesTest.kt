package com.snipsearch.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretBytesTest {
    @Test
    fun adoptOwnsTheArrayAndCloseZeroesIt() {
        val raw = byteArrayOf(1, 2, 3, 4)
        val secret = SecretBytes.adopt(raw)
        secret.read { assertSame(raw, it) }
        val copy = secret.copyOf()
        copy[0] = 9
        assertEquals(1.toByte(), raw[0])
        assertEquals(4, secret.size)
        secret.close()
        assertTrue(raw.all { it == 0.toByte() })
        assertTrue(secret.isClosed)
        secret.close()
        assertThrows(IllegalStateException::class.java) { secret.read { } }
        assertThrows(IllegalStateException::class.java) { secret.copyOf() }
        assertThrows(IllegalStateException::class.java) { secret.size }
        assertEquals("SecretBytes(<redacted>)", secret.toString())
        assertFalse(secret.toString().contains("1"))
        assertArrayEquals(byteArrayOf(9, 2, 3, 4), copy)
    }
}
