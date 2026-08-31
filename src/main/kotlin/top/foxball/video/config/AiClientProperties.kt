package top.foxball.video.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** AI 模型客户端的服务端连接和代理配置，对应 `ai.client.*`。 */
@ConfigurationProperties(prefix = "ai.client")
data class AiClientProperties(
    var connectionProfiles: Map<String, ConnectionProfile> = emptyMap(),
    var defaultProfile: ConnectionProfile = ConnectionProfile(),
    var modelCatalogTtl: Duration = Duration.ofMinutes(10),
) {
    /** 每个提供商可独立覆盖的网络配置。 */
    data class ConnectionProfile(
        var proxyMode: ProxyMode = ProxyMode.DIRECT,
        var proxyHost: String? = null,
        var proxyPort: Int? = null,
        var proxyUsername: String? = null,
        var proxyPassword: String? = null,
        var nonProxyHosts: List<String> = emptyList(),
        var dnsMode: DnsMode = DnsMode.LOCAL,
        var proxyRemoteDnsAllowedHosts: List<String> = emptyList(),
        var allowPrivateNetwork: Boolean = false,
        var connectTimeout: Duration = Duration.ofSeconds(10),
        var responseTimeout: Duration = Duration.ofSeconds(90),
        var maxInFlightChatRequests: Int = 8,
        var maxInFlightImageRequests: Int = 2,
        var maxInFlightModelListRequests: Int = 2,
    )

    enum class ProxyMode { DIRECT, HTTP, SOCKS5 }

    enum class DnsMode { LOCAL, PROXY }
}
