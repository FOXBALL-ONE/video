package top.foxball.video.ai

import org.springframework.stereotype.Component
import top.foxball.video.config.AiClientProperties
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.entity.jdbc.apiType
import java.util.concurrent.ConcurrentHashMap

/** 将数据库中的提供商配置与应用连接档案组合为一次可调用的 AI 连接。 */
@Component
class AiProviderResolver(
    private val repository: AiModelProviderConfigRepository,
    private val properties: AiClientProperties,
    private val transportFactory: ProxyAwareHttpTransportFactory,
) {
    private val uriPolicy = AiProviderUriPolicy()
    private val cachedProfileKeys = ConcurrentHashMap<Long, String>()

    /**
     * 从数据库读取并校验已保存的提供商配置，生成可直接调用的连接对象。
     * 会检查启用状态、API 能力类型，并在连接档案变化时失效旧的 HTTP 客户端缓存。
     *
     * @param providerConfigId 提供商配置主键。
     * @param expectedApiType 调用方要求的能力类型，为空时不限制类型。
     * @return 包含脱敏网络档案和 API 密钥的内部连接对象。
     * @throws AiConfigurationException 配置不存在、停用或校验失败时抛出。
     */
    fun resolveSaved(providerConfigId: Long, expectedApiType: AiApiType? = null): AiProviderConnection {
        val config = repository.findById(providerConfigId).orElseThrow {
            AiConfigurationException("AI 提供商配置不存在")
        }
        if (!config.enabled) throw AiConfigurationException("AI 提供商配置已停用")
        val configuredApiType = config.apiType
        if (expectedApiType != null && configuredApiType != expectedApiType) {
            throw AiConfigurationException("该 API 配置仅支持 ${configuredApiType.name} 类型调用")
        }
        val connection = resolve(
            providerConfigId = requireNotNull(config.id),
            provider = config.provider,
            baseUrl = config.baseUrl,
            apiKey = config.secretKey,
            connectionSettings = AiProviderConnectionSettings.fromStorage(config.connectionConfig),
            profileVersion = config.updatedAt?.toString().orEmpty(),
            apiType = configuredApiType,
        )
        val cacheKey = requireNotNull(connection.profile.cacheKey)
        cachedProfileKeys.put(requireNotNull(config.id), cacheKey)?.takeIf { it != cacheKey }?.let(transportFactory::invalidate)
        return connection
    }

    /**
     * 根据调用方临时提供的参数构造连接，用于新增配置前的连通性测试。
     * 临时连接不写入数据库，也不会使用可长期缓存的连接键。
     */
    fun resolveTransient(
        provider: String,
        baseUrl: String,
        apiKey: String,
        connectionSettings: AiProviderConnectionSettings = AiProviderConnectionSettings(),
    ): AiProviderConnection = resolve(null, provider, baseUrl, apiKey, connectionSettings, "", null)

    /** 统一完成提供商名称、连接档案、地址及目标主机的规范化与校验。 */
    private fun resolve(
        providerConfigId: Long?,
        provider: String,
        baseUrl: String,
        apiKey: String,
        connectionSettings: AiProviderConnectionSettings,
        profileVersion: String,
        apiType: AiApiType?,
    ): AiProviderConnection {
        val normalizedProvider = provider.trim().ifBlank { "default" }
        if (apiKey.isBlank()) throw AiConfigurationException("AI 服务 API Key 不能为空")
        val source = properties.connectionProfiles[normalizedProvider] ?: properties.defaultProfile
        val profileName = providerConfigId?.let { "provider:$it" } ?: "transient:$normalizedProvider"
        val cacheKey = providerConfigId?.let { "$profileName:$profileVersion" }
        val profile = connectionSettings.resolveProfile(
            source = source,
            name = profileName,
            cacheKey = cacheKey,
        )
        validateProfile(profile)
        val baseUri = uriPolicy.normalizeBaseUri(baseUrl)
        uriPolicy.validateDestination(baseUri, profile)
        return AiProviderConnection(providerConfigId, normalizedProvider, baseUri, apiKey, profile, apiType)
    }

    /** 校验超时、并发上限及代理主机端口等连接档案参数。 */
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
