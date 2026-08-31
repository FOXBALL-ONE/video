package top.foxball.video.ai

import java.net.InetAddress
import java.net.URI

/** 规范化 OpenAI 兼容根地址并在请求前执行目标网络校验。 */
class AiProviderUriPolicy {
    fun normalizeBaseUri(baseUrl: String): URI {
        val uri = try {
            URI.create(baseUrl.trim())
        } catch (exception: IllegalArgumentException) {
            throw AiConfigurationException("AI 服务地址格式不正确")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw AiConfigurationException("AI 服务地址必须是有效的 HTTP(S) 地址")
        }
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) {
            throw AiConfigurationException("AI 服务地址不能包含账号、查询参数或片段")
        }
        val path = uri.path.trimEnd('/').removeSuffix("/v1")
        return URI(uri.scheme.lowercase(), uri.authority, path, null, null)
    }

    fun endpoint(baseUri: URI, resource: String): URI {
        val pathPrefix = baseUri.path.trimEnd('/')
        return URI(baseUri.scheme, baseUri.authority, "$pathPrefix/v1/${resource.trimStart('/')}", null, null)
    }

    fun validateDestination(uri: URI, profile: AiConnectionProfile) {
        val host = requireNotNull(uri.host)
        if (profile.dnsMode == AiDnsMode.PROXY) {
            if (profile.proxyMode == AiProxyMode.DIRECT || profile.proxyRemoteDnsAllowedHosts.none { matchesHost(it, host) }) {
                throw AiConfigurationException("代理 DNS 模式仅允许访问已批准的公开主机")
            }
            return
        }
        if (profile.allowPrivateNetwork) return
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (exception: Exception) {
            throw AiTransportException("无法解析模型服务主机", exception)
        }
        if (addresses.any(::isPrivateOrReserved)) {
            throw AiConfigurationException("模型服务地址不能指向私网或保留地址")
        }
    }

    fun matchesHost(pattern: String, host: String): Boolean {
        val normalizedPattern = pattern.trim().lowercase()
        val normalizedHost = host.lowercase()
        return normalizedPattern == normalizedHost ||
            normalizedPattern.startsWith("*.") && normalizedHost.endsWith(normalizedPattern.removePrefix("*"))
    }

    private fun isPrivateOrReserved(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
}
