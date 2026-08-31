package top.foxball.video.ai

import tools.jackson.databind.JsonNode

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
