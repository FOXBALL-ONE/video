package top.foxball.video.ai

import io.netty.channel.ChannelOption
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import reactor.netty.transport.ProxyProvider
import java.util.concurrent.ConcurrentHashMap

/** 为每个不可变连接档案创建复用的、带代理与超时控制的 WebClient。 */
@Component
class ProxyAwareHttpTransportFactory {
    private val clients = ConcurrentHashMap<TransportCacheKey, WebClient>()

    fun client(profile: AiConnectionProfile): WebClient =
        clients.computeIfAbsent(TransportCacheKey.from(profile)) { createClient(profile) }

    private fun createClient(profile: AiConnectionProfile): WebClient {
        var httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, profile.connectTimeout.toMillis().toInt())
            .responseTimeout(profile.responseTimeout)
        if (profile.proxyMode != AiProxyMode.DIRECT) {
            httpClient = httpClient.proxy { proxy ->
                proxy.type(if (profile.proxyMode == AiProxyMode.HTTP) ProxyProvider.Proxy.HTTP else ProxyProvider.Proxy.SOCKS5)
                    .host(requireNotNull(profile.proxyHost))
                    .port(requireNotNull(profile.proxyPort))
                    .apply {
                        if (!profile.proxyUsername.isNullOrBlank()) username(requireNotNull(profile.proxyUsername))
                        if (!profile.proxyPassword.isNullOrBlank()) password { profile.proxyPassword }
                        if (profile.nonProxyHosts.isNotEmpty()) nonProxyHosts(profile.nonProxyHosts.joinToString("|"))
                    }
            }
        }
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024) }
            .build()
    }

    /** 缓存键不包含代理用户名和密码，避免将密钥以键对象形式长期保留。 */
    private data class TransportCacheKey(
        val name: String,
        val proxyMode: AiProxyMode,
        val proxyHost: String?,
        val proxyPort: Int?,
        val nonProxyHosts: List<String>,
        val dnsMode: AiDnsMode,
        val connectTimeoutMillis: Long,
        val responseTimeoutMillis: Long,
    ) {
        companion object {
            fun from(profile: AiConnectionProfile) = TransportCacheKey(
                profile.name,
                profile.proxyMode,
                profile.proxyHost,
                profile.proxyPort,
                profile.nonProxyHosts,
                profile.dnsMode,
                profile.connectTimeout.toMillis(),
                profile.responseTimeout.toMillis(),
            )
        }
    }
}
