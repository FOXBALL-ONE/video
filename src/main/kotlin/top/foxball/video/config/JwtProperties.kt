package top.foxball.video.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "auth.jwt")
data class JwtProperties(
    val secret: String,
    val ttlSeconds: Long = 3600,
)
