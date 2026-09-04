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

    /** 根据连接档案取得可复用的 WebClient；临时档案每次创建独立客户端。 */
    fun client(profile: AiConnectionProfile): WebClient {
        val cacheKey = profile.cacheKey ?: return createClient(profile)
        return clients.computeIfAbsent(TransportCacheKey.from(profile, cacheKey)) { createClient(profile) }
    }

    /** 删除指定档案版本对应的客户端缓存，使后续请求使用新配置。 */
    fun invalidate(cacheKey: String) {
        clients.entries.removeIf { it.key.cacheKey == cacheKey }
    }

    /** 创建带连接超时、响应超时和可选代理认证的 Reactor Netty 客户端。 */
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
        val cacheKey: String,
        val proxyMode: AiProxyMode,
        val proxyHost: String?,
        val proxyPort: Int?,
        val nonProxyHosts: List<String>,
        val dnsMode: AiDnsMode,
        val connectTimeoutMillis: Long,
        val responseTimeoutMillis: Long,
    ) {
        companion object {
            /** 从连接档案提取不含代理密码的缓存键字段。 */
            fun from(profile: AiConnectionProfile, cacheKey: String) = TransportCacheKey(
                cacheKey,
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
