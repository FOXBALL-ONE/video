package top.foxball.video.ai

import java.net.InetAddress
import java.net.URI

/** 规范化 OpenAI 兼容根地址并在请求前执行目标网络校验。 */
class AiProviderUriPolicy {
    /**
     * 规范化提供商根地址，去除末尾斜杠及重复的 `/v1` 路径。
     * 同时拒绝非 HTTP(S)、携带用户信息、查询参数或片段的地址。
     *
     * @param baseUrl 配置页面输入的模型服务地址。
     * @return 可用于拼接固定 API 路径的规范化 URI。
     * @throws AiConfigurationException 地址格式或安全约束不满足时抛出。
     */
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

    /** 将规范化根地址与资源名拼接为 `/v1/{resource}` 端点 URI。 */
    fun endpoint(baseUri: URI, resource: String): URI {
        val pathPrefix = baseUri.path.trimEnd('/')
        return URI(baseUri.scheme, baseUri.authority, "$pathPrefix/v1/${resource.trimStart('/')}", null, null)
    }

    /**
     * 在发起网络请求前校验目标主机，阻止访问私网、环回及保留地址。
     * 代理 DNS 模式下改为检查主机是否命中显式批准的域名列表。
     *
     * @throws AiConfigurationException 目标违反网络访问策略时抛出。
     * @throws AiTransportException 主机 DNS 解析失败时抛出。
     */
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

    /** 判断主机是否等于精确域名或匹配 `*.example.com` 形式的通配域名。 */
    fun matchesHost(pattern: String, host: String): Boolean {
        val normalizedPattern = pattern.trim().lowercase()
        val normalizedHost = host.lowercase()
        return normalizedPattern == normalizedHost ||
            normalizedPattern.startsWith("*.") && normalizedHost.endsWith(normalizedPattern.removePrefix("*"))
    }

    /** 判断 IP 地址是否属于本地、私有、链路本地、多播或其他保留范围。 */
    private fun isPrivateOrReserved(address: InetAddress): Boolean =
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
}
