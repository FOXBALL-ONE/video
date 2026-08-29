package top.foxball.video.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** 仅用于本地/测试环境的固定管理员 Token 配置，默认关闭。 */
@ConfigurationProperties(prefix = "auth.test-admin")
data class TestAdminProperties(
    val enabled: Boolean = false,
    val token: String = "test-admin-token",
    val username: String = "test-admin",
    val email: String = "test-admin@localhost",
    val password: String = "admin",
)
