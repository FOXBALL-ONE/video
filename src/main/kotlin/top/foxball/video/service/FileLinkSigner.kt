package top.foxball.video.service

import org.springframework.stereotype.Component
import top.foxball.video.config.FileProperties
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SignedDownloadLink(
    val scope: String,
    val expiresAt: Instant,
    val nonce: String,
    val signature: String,
)

/** 使用 HMAC 绑定文件、授权范围和过期时间的短期下载链接签名器。 */
@Component
class FileLinkSigner(private val properties: FileProperties) {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun sign(fileId: UUID, scope: String, ttlSeconds: Long): SignedDownloadLink {
        require(ttlSeconds > 0) { "File download link TTL must be positive." }
        require(scope.isNotBlank() && scope.length <= MAX_SCOPE_LENGTH) { "File download scope is invalid." }
        val expires = Instant.now().plusSeconds(ttlSeconds)
        val nonce = ByteArray(18).also(random::nextBytes).let(encoder::encodeToString)
        return SignedDownloadLink(scope, expires, nonce, signature(fileId, scope, expires.epochSecond, nonce))
    }

    fun isValid(fileId: UUID, scope: String, expiresAtEpochSeconds: Long, nonce: String, signature: String): Boolean {
        if (expiresAtEpochSeconds <= Instant.now().epochSecond) return false
        if (scope.isBlank() || scope.length > MAX_SCOPE_LENGTH || nonce.isBlank() || nonce.length > MAX_NONCE_LENGTH) return false
        val expected = signature(fileId, scope, expiresAtEpochSeconds, nonce)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.US_ASCII),
            signature.toByteArray(StandardCharsets.US_ASCII),
        )
    }

    private fun signature(fileId: UUID, scope: String, expires: Long, nonce: String): String {
        val payload = "$fileId\n$scope\n$expires\n$nonce".toByteArray(StandardCharsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.signingSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(payload))
    }

    private companion object {
        const val MAX_SCOPE_LENGTH = 128
        const val MAX_NONCE_LENGTH = 64
    }
}
