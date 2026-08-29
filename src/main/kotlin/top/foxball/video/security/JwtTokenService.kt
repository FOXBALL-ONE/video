package top.foxball.video.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import top.foxball.video.config.JwtProperties
import top.foxball.video.entity.jdbc.User
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Date
import java.util.UUID
import top.foxball.video.service.AccessControlService

data class JwtSession(val userId: Long, val username: String)

@Service
class JwtTokenService(
    private val properties: JwtProperties,
    private val redis: StringRedisTemplate,
) {
    private val key by lazy { Keys.hmacShaKeyFor(properties.secret.toByteArray(StandardCharsets.UTF_8)) }

    fun issue(user: User): String {
        val tokenId = UUID.randomUUID().toString()
        val now = Date()
        val expires = Date(now.time + properties.ttlSeconds * 1000)
        val token = Jwts.builder()
            .id(tokenId).subject(requireNotNull(user.id).toString())
            .claim("username", user.username)
            .issuedAt(now).expiration(expires).signWith(key).compact()
        redis.opsForValue().set(sessionKey(tokenId), requireNotNull(user.id).toString(), Duration.ofSeconds(properties.ttlSeconds))
        return token
    }

    fun parse(token: String): JwtSession? = try {
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        val id = claims.id ?: return null
        val userId = claims.subject.toLong()
        if (redis.opsForValue().get(sessionKey(id)) != userId.toString()) return null
        JwtSession(userId, claims["username"].toString())
    } catch (_: Exception) { null }

    fun revoke(token: String) {
        runCatching {
            val id = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.id
            if (id != null) redis.delete(sessionKey(id))
        }
    }

    private fun sessionKey(id: String) = "auth:session:$id"

    fun testAdminEnabled(userId: Long): Boolean = redis.opsForValue().get(AccessControlService.TEST_ADMIN_STATE_KEY) == userId.toString()

    fun activateTestAdmin(userId: Long) {
        redis.opsForValue().set(AccessControlService.TEST_ADMIN_STATE_KEY, userId.toString())
    }

    fun deactivateTestAdmin() {
        redis.delete(AccessControlService.TEST_ADMIN_STATE_KEY)
    }
}
