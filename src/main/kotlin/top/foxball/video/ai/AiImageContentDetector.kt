package top.foxball.video.ai

/** 依据文件签名识别模型返回的栅格图片格式，避免信任不可靠的 MIME 头。 */
object AiImageContentDetector {
    fun detect(bytes: ByteArray): String = when {
        startsWith(bytes, PNG_SIGNATURE) -> "image/png"
        startsWith(bytes, JPEG_SIGNATURE) -> "image/jpeg"
        startsWith(bytes, RIFF_SIGNATURE) && bytes.size >= 12 && bytes.copyOfRange(8, 12).contentEquals(WEBP_MARKER) -> "image/webp"
        startsWith(bytes, GIF87_SIGNATURE) || startsWith(bytes, GIF89_SIGNATURE) -> "image/gif"
        else -> throw AiProtocolException("模型返回了无法识别的图片数据")
    }

    private fun startsWith(bytes: ByteArray, signature: ByteArray): Boolean =
        bytes.size >= signature.size && bytes.copyOfRange(0, signature.size).contentEquals(signature)

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP_MARKER = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    private val GIF87_SIGNATURE = "GIF87a".encodeToByteArray()
    private val GIF89_SIGNATURE = "GIF89a".encodeToByteArray()
}
