package top.foxball.video.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 文件上传与签名下载配置，对应 `file.*` 配置项。 */
@ConfigurationProperties(prefix = "file")
data class FileProperties(
    var storagePath: String = "./storage",
    var baseUrl: String = "http://localhost:8080",
    var signingSecret: String = "change-me-change-me-change-me-change-me",
    var downloadTokenTtlSeconds: Long = 300,
    var maxBatchSize: Int = 20,
    var maxFileSizeBytes: Long = 104_857_600,
    var signing: Signing = Signing(),
) {
    data class Signing(
        var publicTtlSeconds: Long = 60,
        var userTtlSeconds: Long = -1,
        var adminTtlSeconds: Long = 180,
        var orderTtlSeconds: Long = 300,
    ) {
        fun resolvedUserTtl(fallback: Long): Long = if (userTtlSeconds > 0) userTtlSeconds else fallback
    }
}
