package top.foxball.video.ai

import tools.jackson.databind.JsonNode
import top.foxball.video.config.AiClientProperties
import java.time.Duration

/** 可由 API 管理页覆盖的提供商网络设置；缺省时沿用应用级连接档案。 */
data class AiProviderConnectionSettings(
    val useDefaultTransport: Boolean = true,
    val proxyMode: AiProxyMode = AiProxyMode.DIRECT,
    val proxyHost: String? = null,
    val proxyPort: Int? = null,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val nonProxyHosts: List<String> = emptyList(),
    val dnsMode: AiDnsMode = AiDnsMode.LOCAL,
    val proxyRemoteDnsAllowedHosts: List<String> = emptyList(),
    val allowPrivateNetwork: Boolean = false,
    val connectTimeoutSeconds: Long = 10,
    val responseTimeoutSeconds: Long = 90,
) {
    fun toStorage(): Map<String, String> {
        if (useDefaultTransport) return mapOf("use_default_transport" to "true")
        return buildMap {
            put("use_default_transport", "false")
            put("proxy_mode", proxyMode.name)
            if (proxyMode != AiProxyMode.DIRECT) {
                proxyHost?.trim()?.takeIf(String::isNotBlank)?.let { put("proxy_host", it) }
                proxyPort?.let { put("proxy_port", it.toString()) }
                proxyUsername?.trim()?.takeIf(String::isNotBlank)?.let { put("proxy_username", it) }
                proxyPassword?.takeIf(String::isNotBlank)?.let { put("proxy_password", it) }
                if (nonProxyHosts.isNotEmpty()) put("non_proxy_hosts", nonProxyHosts.joinToString(","))
                put("dns_mode", dnsMode.name)
                if (proxyRemoteDnsAllowedHosts.isNotEmpty()) {
                    put("proxy_remote_dns_allowed_hosts", proxyRemoteDnsAllowedHosts.joinToString(","))
                }
            } else {
                put("dns_mode", AiDnsMode.LOCAL.name)
            }
            put("allow_private_network", allowPrivateNetwork.toString())
            put("connect_timeout_seconds", connectTimeoutSeconds.toString())
            put("response_timeout_seconds", responseTimeoutSeconds.toString())
        }
    }

    fun resolveProfile(
        source: AiClientProperties.ConnectionProfile,
        name: String,
        cacheKey: String?,
    ): AiConnectionProfile {
        if (useDefaultTransport) {
            return AiConnectionProfile(
                name = name,
                proxyMode = AiProxyMode.valueOf(source.proxyMode.name),
                proxyHost = source.proxyHost?.trim()?.ifBlank { null },
                proxyPort = source.proxyPort,
                proxyUsername = source.proxyUsername?.trim()?.ifBlank { null },
                proxyPassword = source.proxyPassword?.takeIf(String::isNotBlank),
                nonProxyHosts = source.nonProxyHosts.map(String::trim).filter(String::isNotBlank),
                dnsMode = AiDnsMode.valueOf(source.dnsMode.name),
                proxyRemoteDnsAllowedHosts = source.proxyRemoteDnsAllowedHosts.map(String::trim).filter(String::isNotBlank),
                allowPrivateNetwork = source.allowPrivateNetwork,
                connectTimeout = source.connectTimeout,
                responseTimeout = source.responseTimeout,
                maxInFlightChatRequests = source.maxInFlightChatRequests,
                maxInFlightImageRequests = source.maxInFlightImageRequests,
                maxInFlightModelListRequests = source.maxInFlightModelListRequests,
                cacheKey = cacheKey,
            )
        }
        return AiConnectionProfile(
            name = name,
            proxyMode = proxyMode,
            proxyHost = proxyHost?.trim()?.ifBlank { null },
            proxyPort = proxyPort,
            proxyUsername = proxyUsername?.trim()?.ifBlank { null },
            proxyPassword = proxyPassword?.takeIf(String::isNotBlank),
            nonProxyHosts = nonProxyHosts.map(String::trim).filter(String::isNotBlank),
            dnsMode = dnsMode,
            proxyRemoteDnsAllowedHosts = proxyRemoteDnsAllowedHosts.map(String::trim).filter(String::isNotBlank),
            allowPrivateNetwork = allowPrivateNetwork,
            connectTimeout = Duration.ofSeconds(connectTimeoutSeconds),
            responseTimeout = Duration.ofSeconds(responseTimeoutSeconds),
            maxInFlightChatRequests = source.maxInFlightChatRequests,
            maxInFlightImageRequests = source.maxInFlightImageRequests,
            maxInFlightModelListRequests = source.maxInFlightModelListRequests,
            cacheKey = cacheKey,
        )
    }

    companion object {
        fun fromStorage(storage: Map<String, String>?): AiProviderConnectionSettings {
            if (storage.isNullOrEmpty()) return AiProviderConnectionSettings()
            val useDefaultTransport = storage["use_default_transport"]?.toBooleanStrictOrNull() ?: true
            return AiProviderConnectionSettings(
                useDefaultTransport = useDefaultTransport,
                proxyMode = storage["proxy_mode"]?.let {
                    runCatching { AiProxyMode.valueOf(it) }
                        .getOrElse { throw AiConfigurationException("AI 代理模式配置不正确") }
                } ?: AiProxyMode.DIRECT,
                proxyHost = storage["proxy_host"],
                proxyPort = storage["proxy_port"]?.toIntOrNull(),
                proxyUsername = storage["proxy_username"],
                proxyPassword = storage["proxy_password"],
                nonProxyHosts = storage["non_proxy_hosts"]?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
                dnsMode = storage["dns_mode"]?.let {
                    runCatching { AiDnsMode.valueOf(it) }
                        .getOrElse { throw AiConfigurationException("AI DNS 模式配置不正确") }
                } ?: AiDnsMode.LOCAL,
                proxyRemoteDnsAllowedHosts = storage["proxy_remote_dns_allowed_hosts"]?.split(',')?.map(String::trim)
                    ?.filter(String::isNotBlank).orEmpty(),
                allowPrivateNetwork = storage["allow_private_network"]?.toBooleanStrictOrNull() ?: false,
                connectTimeoutSeconds = storage["connect_timeout_seconds"]?.toLongOrNull() ?: 10,
                responseTimeoutSeconds = storage["response_timeout_seconds"]?.toLongOrNull() ?: 90,
            )
        }
    }
}

/** 经校验、可用于单次上游调用的提供商连接。密钥仅在客户端内部使用。 */
data class AiProviderConnection(
    val providerConfigId: Long?,
    val provider: String,
    val baseUri: java.net.URI,
    val apiKey: String,
    val profile: AiConnectionProfile,
)

/** 代理、DNS、超时和并发限制的不可变连接档案。 */
data class AiConnectionProfile(
    val name: String,
    val proxyMode: AiProxyMode,
    val proxyHost: String?,
    val proxyPort: Int?,
    val proxyUsername: String?,
    val proxyPassword: String?,
    val nonProxyHosts: List<String>,
    val dnsMode: AiDnsMode,
    val proxyRemoteDnsAllowedHosts: List<String>,
    val allowPrivateNetwork: Boolean,
    val connectTimeout: java.time.Duration,
    val responseTimeout: java.time.Duration,
    val maxInFlightChatRequests: Int,
    val maxInFlightImageRequests: Int,
    val maxInFlightModelListRequests: Int,
    /** null 时不缓存，避免临时连接携带的代理密钥长期驻留内存。 */
    val cacheKey: String? = name,
)

enum class AiProxyMode { DIRECT, HTTP, SOCKS5 }

enum class AiDnsMode { LOCAL, PROXY }

data class AiModelDescriptor(
    val id: String,
    val ownedBy: String? = null,
)

enum class AiChatRole { SYSTEM, USER, ASSISTANT, TOOL }

data class AiChatToolDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonNode,
)

data class AiChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class AiChatMessage(
    val role: AiChatRole,
    val content: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AiChatToolCall> = emptyList(),
)

data class AiChatCompletionCommand(
    val providerConfigId: Long,
    val model: String,
    val messages: List<AiChatMessage>,
    val temperature: Double? = null,
    val maxCompletionTokens: Int? = null,
    val tools: List<AiChatToolDefinition> = emptyList(),
    val requestId: String? = null,
)

data class AiChatChoice(
    val index: Int,
    val message: AiChatMessage,
    val finishReason: String?,
)

data class AiTokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

data class AiChatCompletionResult(
    val id: String?,
    val model: String?,
    val choices: List<AiChatChoice>,
    val usage: AiTokenUsage? = null,
) {
    val primaryChoice: AiChatChoice? get() = choices.firstOrNull()
}

data class AiChatCompletionDelta(
    val id: String?,
    val choiceIndex: Int,
    val role: AiChatRole?,
    val content: String?,
    val toolCalls: List<AiChatToolCall>,
    val finishReason: String?,
)

enum class AiImageResponseFormat(val wireValue: String) { URL("url"), B64_JSON("b64_json") }

data class AiImageGenerationCommand(
    val providerConfigId: Long,
    val model: String,
    val prompt: String,
    val count: Int? = null,
    val size: String? = null,
    val quality: String? = null,
    val style: String? = null,
    val responseFormat: AiImageResponseFormat = AiImageResponseFormat.B64_JSON,
    val requestId: String? = null,
)

data class AiImageInput(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

data class AiImageEditCommand(
    val providerConfigId: Long,
    val model: String,
    val prompt: String,
    val images: List<AiImageInput>,
    val mask: AiImageInput? = null,
    val count: Int? = null,
    val size: String? = null,
    val responseFormat: AiImageResponseFormat = AiImageResponseFormat.B64_JSON,
    val requestId: String? = null,
)

data class AiGeneratedImage(
    val url: String? = null,
    val base64Data: String? = null,
    val revisedPrompt: String? = null,
)

data class AiImageGenerationResult(
    val createdAtEpochSeconds: Long?,
    val images: List<AiGeneratedImage>,
)
