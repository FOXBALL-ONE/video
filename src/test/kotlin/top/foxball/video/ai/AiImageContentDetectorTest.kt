package top.foxball.video.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AiImageContentDetectorTest {
    @Test
    fun `detects supported image signatures`() {
        assertEquals("image/png", AiImageContentDetector.detect(PNG))
        assertEquals("image/jpeg", AiImageContentDetector.detect(JPEG))
        assertEquals("image/webp", AiImageContentDetector.detect(WEBP))
        assertEquals("image/gif", AiImageContentDetector.detect("GIF89a".encodeToByteArray()))
    }

    @Test
    fun `rejects non image bytes`() {
        assertFailsWith<AiProtocolException> {
            AiImageContentDetector.detect("not-an-image".encodeToByteArray())
        }
    }

    private companion object {
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val WEBP = byteArrayOf(0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50)
    }
}
