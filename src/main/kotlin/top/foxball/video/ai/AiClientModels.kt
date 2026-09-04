package top.foxball.video.ai

import tools.jackson.databind.JsonNode
import top.foxball.video.config.AiClientProperties
import java.time.Duration

/** AI API 能力类型，决定配置可被哪类业务调用。 */
enum class AiApiType { CHAT, IMAGE, VIDEO }

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
    /** 将连接设置序列化为数据库配置键值，省略默认值中的敏感或无关字段。 */
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

    /** 将当前设置与应用级连接档案合并，生成单次调用使用的不可变档案。 */
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
        /** 从数据库键值读取连接设置；缺失或空配置使用安全默认值。 */
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

/**
 * 一次 AI 上游调用所需的提供商地址、密钥和连接档案。
 * 连接已在解析阶段完成校验，密钥仅在客户端内部使用。
 */
data class AiProviderConnection(
    val providerConfigId: Long?,
    val provider: String,
    val baseUri: java.net.URI,
    val apiKey: String,
    val profile: AiConnectionProfile,
    /** 已保存配置的能力类型；临时连接为空以保持模型探测兼容。 */
    val apiType: AiApiType? = null,
)

/** 经过校验且可复用的代理、DNS、超时和并发限制不可变配置。 */
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

/** 模型目录中的单个模型及其可选归属信息。 */
data class AiModelDescriptor(
    val id: String,
    val ownedBy: String? = null,
)

enum class AiChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** 提交给聊天模型的函数工具定义及 JSON Schema 参数。 */
data class AiChatToolDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonNode,
)

/** 模型返回或请求继续执行的单个工具调用。 */
data class AiChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** 统一表示系统、用户、助手和工具角色的聊天消息。 */
data class AiChatMessage(
    val role: AiChatRole,
    val content: String? = null,
    val name: String? = null,
    val toolCallId: String? = null,
    val toolCalls: List<AiChatToolCall> = emptyList(),
)

/** 聊天补全请求命令，包含模型、消息、采样参数和可选工具。 */
data class AiChatCompletionCommand(
    val providerConfigId: Long,
    val model: String,
    val messages: List<AiChatMessage>,
    val temperature: Double? = null,
    val maxCompletionTokens: Int? = null,
    /** 受控结构化输出格式，例如 json_object 或 json_schema；不得传入任意供应商 JSON。 */
    val responseFormat: String? = null,
    /** responseFormat 为 json_schema 时使用的服务端校验 schema。 */
    val responseSchema: JsonNode? = null,
    val tools: List<AiChatToolDefinition> = emptyList(),
    val requestId: String? = null,
)

/** 聊天补全响应中的一个候选答案及结束原因。 */
data class AiChatChoice(
    val index: Int,
    val message: AiChatMessage,
    val finishReason: String?,
)

/** 上游返回的输入、输出及总 token 用量统计。 */
data class AiTokenUsage(
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val totalTokens: Int? = null,
)

/** 非流式聊天补全的统一响应模型。 */
data class AiChatCompletionResult(
    val id: String?,
    val model: String?,
    val choices: List<AiChatChoice>,
    val usage: AiTokenUsage? = null,
) {
    /** 便于业务层读取的首个候选答案；没有候选时返回 null。 */
    val primaryChoice: AiChatChoice? get() = choices.firstOrNull()
}

/** 流式聊天响应中的单个增量事件。 */
data class AiChatCompletionDelta(
    val id: String?,
    val choiceIndex: Int,
    val role: AiChatRole?,
    val content: String?,
    val toolCalls: List<AiChatToolCall>,
    val finishReason: String?,
)

enum class AiImageResponseFormat(val wireValue: String) { URL("url"), B64_JSON("b64_json") }

/** 图像生成请求命令及可选尺寸、质量、风格和返回格式。 */
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

/** 上传给图像编辑或视频参考帧的图片文件内容。 */
data class AiImageInput(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/** 图像编辑请求命令，支持多张输入图片及可选蒙版。 */
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

/** 单张生成图片，按上游响应可能包含 URL 或 base64 数据。 */
data class AiGeneratedImage(
    val url: String? = null,
    val base64Data: String? = null,
    val revisedPrompt: String? = null,
)

/** 图像生成或编辑接口的统一结果及创建时间。 */
data class AiImageGenerationResult(
    val createdAtEpochSeconds: Long?,
    val images: List<AiGeneratedImage>,
)

/** OpenAI Videos API 的视频生成请求命令；视频接口通常异步返回任务。 */
data class AiVideoGenerationCommand(
    val providerConfigId: Long,
    val model: String,
    val prompt: String,
    val size: String = "1280x720",
    val seconds: Int = 8,
    val inputReference: AiImageInput? = null,
    val requestId: String? = null,
)

enum class AiVideoStatus { QUEUED, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, UNKNOWN }

/** 视频任务的统一状态、进度、时间戳和错误信息。 */
data class AiVideoGenerationResult(
    val id: String,
    val model: String?,
    val status: AiVideoStatus,
    val progress: Int? = null,
    val createdAtEpochSeconds: Long? = null,
    val completedAtEpochSeconds: Long? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

/** 视频下载结果，包含响应 MIME 类型、文件名和完整二进制内容。 */
data class AiVideoContent(
    val bytes: ByteArray,
    val contentType: String,
    val filename: String,
)
