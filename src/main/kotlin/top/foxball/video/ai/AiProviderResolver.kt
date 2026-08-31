package top.foxball.video.ai

import org.springframework.stereotype.Component
import top.foxball.video.config.AiClientProperties
import top.foxball.video.repository.AiModelProviderConfigRepository

/** 将数据库中的提供商配置与应用连接档案组合为一次可调用的 AI 连接。 */
@Component
class AiProviderResolver(
    private val repository: AiModelProviderConfigRepository,
    private val properties: AiClientProperties,
) {
    private val uriPolicy = AiProviderUriPolicy()

    fun resolveSaved(providerConfigId: Long): AiProviderConnection {
        val config = repository.findById(providerConfigId).orElseThrow {
            AiConfigurationException("AI 提供商配置不存在")
        }
        if (!config.enabled) throw AiConfigurationException("AI 提供商配置已停用")
        return resolve(
            providerConfigId = requireNotNull(config.id),
            provider = config.provider,
            baseUrl = config.baseUrl,
            apiKey = config.secretKey,
        )
    }

    fun resolveTransient(provider: String, baseUrl: String, apiKey: String): AiProviderConnection =
        resolve(null, provider, baseUrl, apiKey)

    private fun resolve(
        providerConfigId: Long?,
        provider: String,
        baseUrl: String,
        apiKey: String,
    ): AiProviderConnection {
        val normalizedProvider = provider.trim().ifBlank { "default" }
        if (apiKey.isBlank()) throw AiConfigurationException("AI 服务 API Key 不能为空")
        val profileName = properties.connectionProfiles.keys.firstOrNull { it == normalizedProvider } ?: "default"
        val source = properties.connectionProfiles[normalizedProvider] ?: properties.defaultProfile
        val profile = AiConnectionProfile(
            name = profileName,
            proxyMode = AiProxyMode.valueOf(source.proxyMode.name),
            proxyHost = source.proxyHost?.trim()?.ifBlank { null },
            proxyPort = source.proxyPort,
            proxyUsername = source.proxyUsername?.trim()?.ifBlank { null },
            proxyPassword = source.proxyPassword?.takeIf { it.isNotBlank() },
            nonProxyHosts = source.nonProxyHosts.map(String::trim).filter(String::isNotBlank),
            dnsMode = AiDnsMode.valueOf(source.dnsMode.name),
            proxyRemoteDnsAllowedHosts = source.proxyRemoteDnsAllowedHosts.map(String::trim).filter(String::isNotBlank),
            allowPrivateNetwork = source.allowPrivateNetwork,
            connectTimeout = source.connectTimeout,
            responseTimeout = source.responseTimeout,
            maxInFlightChatRequests = source.maxInFlightChatRequests,
            maxInFlightImageRequests = source.maxInFlightImageRequests,
            maxInFlightModelListRequests = source.maxInFlightModelListRequests,
        )
        validateProfile(profile)
        val baseUri = uriPolicy.normalizeBaseUri(baseUrl)
        uriPolicy.validateDestination(baseUri, profile)
        return AiProviderConnection(providerConfigId, normalizedProvider, baseUri, apiKey, profile)
    }

    private fun validateProfile(profile: AiConnectionProfile) {
        if (profile.connectTimeout.isNegative || profile.connectTimeout.isZero || profile.responseTimeout.isNegative || profile.responseTimeout.isZero) {
            throw AiConfigurationException("AI 连接超时配置必须大于零")
        }
        if (profile.maxInFlightChatRequests < 1 || profile.maxInFlightImageRequests < 1 || profile.maxInFlightModelListRequests < 1) {
            throw AiConfigurationException("AI 并发限制必须大于零")
        }
        if (profile.proxyMode == AiProxyMode.DIRECT) return
        if (profile.proxyHost.isNullOrBlank() || profile.proxyPort == null || profile.proxyPort !in 1..65535) {
            throw AiConfigurationException("代理主机和端口配置不正确")
        }
    }
}
