package io.github.qaisarpipeshift.utter.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmTest {

    @Test
    fun `silence reads as zero level`() {
        assertEquals(0f, Pcm.rms(ByteArray(3_200)), 0.0001f)
        assertEquals(0f, Pcm.rms(ByteArray(0)), 0.0001f)
        assertEquals(0f, Pcm.rms(ByteArray(1)), 0.0001f)
    }

    @Test
    fun `positive full scale reads as full level`() {
        val pcm = ByteArray(3_200) { index -> if (index % 2 == 0) 0xFF.toByte() else 0x7F.toByte() }
        assertEquals(1f, Pcm.rms(pcm), 0.01f)
    }

    @Test
    fun `negative full scale is symmetric`() {
        val pcm = ByteArray(3_200) { index -> if (index % 2 == 0) 0x00.toByte() else 0x80.toByte() }
        assertEquals(1f, Pcm.rms(pcm), 0.01f)
    }

    @Test
    fun `duration follows the sample rate`() {
        assertEquals(1_000L, Pcm.durationMs(ByteArray(32_000)))
        assertEquals(500L, Pcm.durationMs(16_000L))
        assertEquals(0L, Pcm.durationMs(ByteArray(0)))
        assertEquals(1_000L, Pcm.durationMs(ByteArray(16_000), rate = 8_000))
    }

    @Test
    fun `wav header describes mono pcm16`() {
        val dataBytes = 32_000
        val header = Pcm.wavHeader(dataBytes)

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + dataBytes, buffer.getInt(4))
        assertEquals(16, buffer.getInt(16))
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(16_000, buffer.getInt(24))
        assertEquals(32_000, buffer.getInt(28))
        assertEquals(2, buffer.getShort(32).toInt())
        assertEquals(16, buffer.getShort(34).toInt())
        assertEquals(dataBytes, buffer.getInt(40))
    }
}
