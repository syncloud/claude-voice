package org.syncloud.claudevoice

import org.junit.Assert.assertEquals
import org.junit.Test

class WavHeaderTest {
    @Test
    fun riffMagicIsAscii() {
        val riff = "RIFF".toByteArray()
        assertEquals(0x52, riff[0].toInt())
        assertEquals(4, riff.size)
    }
}
