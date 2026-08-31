package top.foxball.video.ai

import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClientRequestException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.util.retry.Retry
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** 使用固定 OpenAI 兼容路径发送模型、对话和图像请求。 */
@Component
class OpenAiCompatibleClient(
    private val transportFactory: ProxyAwareHttpTransportFactory,
    private val invocationGuard: AiInvocationGuard,
    private val objectMapper: ObjectMapper,
    private val properties: top.foxball.video.config.AiClientProperties,
) : AiModelClient {
    private val uriPolicy = AiProviderUriPolicy()
    private val modelCatalog = ConcurrentHashMap<String, CachedModels>()

    override fun listModels(connection: AiProviderConnection): Mono<List<AiModelDescriptor>> {
        val cacheKey = "${connection.providerConfigId ?: connection.baseUri}|${connection.profile.name}"
        val cached = modelCatalog[cacheKey]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) return Mono.just(cached.models)

        return invocationGuard.protect(connection, AiOperation.MODEL_LIST) {
            exchangeJson(connection, "models", AiOperation.MODEL_LIST)
                .map(::parseModels)
                .retryWhen(
                    Retry.backoff(2, Duration.ofMillis(150))
                        .maxBackoff(Duration.ofSeconds(2))
                        .jitter(0.25)
                        .filter { exception ->
                            exception is AiTransportException ||
                                exception is AiProxyException ||
                                exception is AiRateLimitException ||
                                exception is AiUpstreamException && exception.statusCode >= 500
                        },
                )
                .doOnNext { models ->
                    modelCatalog[cacheKey] = CachedModels(models, Instant.now().plus(properties.modelCatalogTtl))
                }
        }
    }

    override fun createChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult> {
        validateCommandConnection(connection, command.providerConfigId)
        val payload = chatPayload(command, stream = false)
        return invocationGuard.protect(connection, AiOperation.CHAT) {
            exchangeJson(connection, "chat/completions", AiOperation.CHAT, payload, command.requestId)
                .map(::parseChatCompletion)
        }
    }

    override fun streamChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta> {
        validateCommandConnection(connection, command.providerConfigId)
        val payload = chatPayload(command, stream = true)
        return invocationGuard.protectFlux(connection, AiOperation.CHAT) {
            val uri = uriPolicy.endpoint(connection.baseUri, "chat/completions")
            transportFactory.client(connection.profile)
                .post()
                .uri(uri)
                .headers { headers -> applyHeaders(headers, connection, command.requestId) }
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .exchangeToFlux<AiChatCompletionDelta> { response ->
                    if (!response.statusCode().is2xxSuccessful) errorFlux(response)
                    else response.bodyToFlux(SSE_TYPE)
                        .mapNotNull(ServerSentEvent<String>::data)
                        .takeWhile { it != "[DONE]" }
                        .map(::parseChatDelta)
                }
                .onErrorMap(::mapTransportException)
        }
    }

    override fun generateImage(
        connection: AiProviderConnection,
        command: AiImageGenerationCommand,
    ): Mono<AiImageGenerationResult> {
        validateCommandConnection(connection, command.providerConfigId)
        require(command.prompt.isNotBlank()) { "图像提示词不能为空" }
        val payload = linkedMapOf<String, Any>(
            "model" to command.model,
            "prompt" to command.prompt,
            "response_format" to command.responseFormat.wireValue,
        ).apply {
            command.count?.let { put("n", it) }
            command.size?.let { put("size", it) }
            command.quality?.let { put("quality", it) }
            command.style?.let { put("style", it) }
        }
        return invocationGuard.protect(connection, AiOperation.IMAGE) {
            exchangeJson(connection, "images/generations", AiOperation.IMAGE, payload, command.requestId)
                .map(::parseImages)
        }
    }

    override fun editImage(
        connection: AiProviderConnection,
        command: AiImageEditCommand,
    ): Mono<AiImageGenerationResult> {
        validateCommandConnection(connection, command.providerConfigId)
        require(command.prompt.isNotBlank()) { "图像提示词不能为空" }
        require(command.images.isNotEmpty()) { "至少需要一张待编辑图片" }
        command.images.forEach(::validateImage)
        command.mask?.let(::validateImage)

        val multipart = MultipartBodyBuilder()
        multipart.part("model", command.model)
        multipart.part("prompt", command.prompt)
        multipart.part("response_format", command.responseFormat.wireValue)
        command.images.forEach { image ->
            multipart.part("image[]", imageResource(image)).contentType(MediaType.parseMediaType(image.contentType))
        }
        command.mask?.let { mask ->
            multipart.part("mask", imageResource(mask)).contentType(MediaType.parseMediaType(mask.contentType))
        }
        command.count?.let { multipart.part("n", it.toString()) }
        command.size?.let { multipart.part("size", it) }

        return invocationGuard.protect(connection, AiOperation.IMAGE) {
            val uri = uriPolicy.endpoint(connection.baseUri, "images/edits")
            transportFactory.client(connection.profile)
                .post()
                .uri(uri)
                .headers { headers -> applyHeaders(headers, connection, command.requestId) }
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchangeToMono { response -> jsonResponse(response) }
                .map(::parseImages)
                .onErrorMap(::mapTransportException)
        }
    }

    private fun exchangeJson(
        connection: AiProviderConnection,
        resource: String,
        operation: AiOperation,
        payload: Any? = null,
        requestId: String? = null,
    ): Mono<JsonNode> {
        val uri = uriPolicy.endpoint(connection.baseUri, resource)
        val request = transportFactory.client(connection.profile)
            .method(if (payload == null) org.springframework.http.HttpMethod.GET else org.springframework.http.HttpMethod.POST)
            .uri(uri)
            .headers { headers -> applyHeaders(headers, connection, requestId) }
            .accept(MediaType.APPLICATION_JSON)
        return (if (payload == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload))
            .exchangeToMono(::jsonResponse)
            .onErrorMap(::mapTransportException)
    }

    private fun jsonResponse(response: ClientResponse): Mono<JsonNode> {
        if (response.statusCode().is2xxSuccessful) {
            return response.bodyToMono(JsonNode::class.java)
                .switchIfEmpty(Mono.error(AiProtocolException("模型服务未返回 JSON 响应")))
        }
        val statusCode = response.statusCode().value()
        val retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()
        return response.bodyToMono(String::class.java).defaultIfEmpty("").flatMap { responseContent ->
            val error: Throwable = when {
                statusCode == 401 || statusCode == 403 -> AiUpstreamAuthenticationException(statusCode, responseContent.take(MAX_ERROR_RESPONSE_CHARS))
                statusCode == 429 -> AiRateLimitException(retryAfter, responseContent.take(MAX_ERROR_RESPONSE_CHARS))
                else -> AiUpstreamException(statusCode, responseContent.take(MAX_ERROR_RESPONSE_CHARS))
            }
            Mono.error(error)
        }
    }

    private fun errorFlux(response: ClientResponse): Flux<AiChatCompletionDelta> =
        jsonResponse(response).thenMany(Flux.error(AiProtocolException("模型服务流式响应格式错误")))

    private fun applyHeaders(headers: HttpHeaders, connection: AiProviderConnection, requestId: String?) {
        headers.setBearerAuth(connection.apiKey)
        headers.set(HttpHeaders.USER_AGENT, "video-ai-client")
        requestId?.takeIf { it.isNotBlank() }?.let { headers.set("X-Request-Id", it) }
    }

    private fun chatPayload(command: AiChatCompletionCommand, stream: Boolean): Map<String, Any> {
        require(command.model.isNotBlank()) { "模型 ID 不能为空" }
        require(command.messages.isNotEmpty()) { "对话消息不能为空" }
        if (command.messages.sumOf { it.content?.length ?: 0 } > MAX_CHAT_CONTENT_CHARS) {
            throw AiConfigurationException("对话内容超过 2 MB 限制")
        }
        return linkedMapOf<String, Any>(
            "model" to command.model,
            "messages" to command.messages.map(::chatMessagePayload),
            "stream" to stream,
        ).apply {
            command.temperature?.let { put("temperature", it) }
            command.maxCompletionTokens?.let { put("max_completion_tokens", it) }
            if (command.tools.isNotEmpty()) put("tools", command.tools.map(::toolPayload))
        }
    }

    private fun chatMessagePayload(message: AiChatMessage): Map<String, Any> {
        if (message.content.isNullOrBlank() && message.toolCalls.isEmpty()) throw AiConfigurationException("对话消息必须包含文本或工具调用")
        if (message.role == AiChatRole.TOOL && message.toolCallId.isNullOrBlank()) throw AiConfigurationException("工具消息缺少 tool_call_id")
        return linkedMapOf<String, Any>("role" to message.role.name.lowercase()).apply {
            message.content?.let { put("content", it) }
            message.name?.let { put("name", it) }
            message.toolCallId?.let { put("tool_call_id", it) }
            if (message.toolCalls.isNotEmpty()) {
                put("tool_calls", message.toolCalls.map { call ->
                    mapOf("id" to call.id, "type" to "function", "function" to mapOf("name" to call.name, "arguments" to call.arguments))
                })
            }
        }
    }

    private fun toolPayload(tool: AiChatToolDefinition): Map<String, Any> {
        if (tool.name.isBlank()) throw AiConfigurationException("工具名称不能为空")
        return mapOf("type" to "function", "function" to linkedMapOf<String, Any>("name" to tool.name, "parameters" to tool.parameters).apply {
            tool.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        })
    }

    private fun parseModels(response: JsonNode): List<AiModelDescriptor> {
        val data = response.path("data")
        if (!data.isArray) throw AiProtocolException("模型服务响应不符合 /v1/models 协议")
        return data.mapNotNull { item ->
            item.fieldString("id")?.let { id -> AiModelDescriptor(id, item.fieldString("owned_by")) }
        }.distinctBy(AiModelDescriptor::id)
    }

    private fun parseChatCompletion(response: JsonNode): AiChatCompletionResult {
        val choices = response.path("choices")
        if (!choices.isArray) throw AiProtocolException("模型服务响应不包含 choices")
        if (choices.isEmpty) throw AiProtocolException("模型服务未返回可用 choices")
        return AiChatCompletionResult(
            id = response.fieldString("id"),
            model = response.fieldString("model"),
            choices = choices.mapNotNull(::parseChatChoice),
            usage = response.path("usage").takeIf { !it.isMissingNode && !it.isNull }?.let(::parseUsage),
        )
    }

    private fun parseChatChoice(choice: JsonNode): AiChatChoice? {
        val message = choice.path("message")
        val role = message.fieldString("role")?.uppercase()?.let { value -> AiChatRole.entries.firstOrNull { it.name == value } } ?: return null
        return AiChatChoice(
            index = choice.fieldInt("index") ?: 0,
            message = AiChatMessage(role, message.fieldString("content"), toolCalls = parseToolCalls(message.path("tool_calls"))),
            finishReason = choice.fieldString("finish_reason"),
        )
    }

    private fun parseChatDelta(event: String): AiChatCompletionDelta {
        val response = try {
            objectMapper.readTree(event)
        } catch (exception: Exception) {
            throw AiProtocolException("模型服务返回了无法解析的 SSE 事件")
        }
        val choice = response.path("choices").firstOrNull() ?: throw AiProtocolException("模型服务 SSE 事件不包含 choices")
        val delta = choice.path("delta")
        return AiChatCompletionDelta(
            id = response.fieldString("id"),
            choiceIndex = choice.fieldInt("index") ?: 0,
            role = delta.fieldString("role")?.uppercase()?.let { value -> AiChatRole.entries.firstOrNull { it.name == value } },
            content = delta.fieldString("content"),
            toolCalls = parseToolCalls(delta.path("tool_calls")),
            finishReason = choice.fieldString("finish_reason"),
        )
    }

    private fun parseUsage(usage: JsonNode): AiTokenUsage = AiTokenUsage(
        inputTokens = usage.fieldInt("prompt_tokens") ?: usage.fieldInt("input_tokens"),
        outputTokens = usage.fieldInt("completion_tokens") ?: usage.fieldInt("output_tokens"),
        totalTokens = usage.fieldInt("total_tokens"),
    )

    private fun parseToolCalls(node: JsonNode): List<AiChatToolCall> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { call ->
            val function = call.path("function")
            val id = call.fieldString("id") ?: return@mapNotNull null
            val name = function.fieldString("name") ?: return@mapNotNull null
            AiChatToolCall(id, name, function.fieldString("arguments").orEmpty())
        }
    }

    private fun parseImages(response: JsonNode): AiImageGenerationResult {
        val data = response.path("data")
        if (!data.isArray) throw AiProtocolException("模型服务响应不包含图像数据")
        return AiImageGenerationResult(
            createdAtEpochSeconds = response.fieldLong("created"),
            images = data.mapNotNull { item ->
                val url = item.fieldString("url")
                val base64 = item.fieldString("b64_json")
                if (url == null && base64 == null) null else AiGeneratedImage(url, base64, item.fieldString("revised_prompt"))
            },
        )
    }

    private fun imageResource(image: AiImageInput): ByteArrayResource = object : ByteArrayResource(image.bytes) {
        override fun getFilename(): String = image.filename
    }

    private fun validateImage(image: AiImageInput) {
        if (image.filename.isBlank() || image.contentType.isBlank() || image.bytes.isEmpty()) throw AiConfigurationException("图像编辑输入不完整")
        if (!image.contentType.startsWith("image/")) throw AiConfigurationException("图像编辑仅支持图片文件")
        if (image.bytes.size > MAX_IMAGE_BYTES) throw AiConfigurationException("单张图像不能超过 20 MB")
    }

    private fun validateCommandConnection(connection: AiProviderConnection, providerConfigId: Long) {
        if (connection.providerConfigId != providerConfigId) throw AiConfigurationException("AI 调用与提供商配置不匹配")
    }

    private fun mapTransportException(exception: Throwable): Throwable = when (exception) {
        is AiClientException -> exception
        is WebClientRequestException -> AiTransportException("无法连接模型服务", exception)
        else -> AiTransportException("模型服务调用失败", exception)
    }

    private fun JsonNode.fieldString(name: String): String? {
        val value = path(name)
        return if (value.isString) value.stringValue().trim().takeIf(String::isNotEmpty) else null
    }

    private fun JsonNode.fieldInt(name: String): Int? {
        val value = path(name)
        return if (value.isInt || value.isLong) value.intValue() else null
    }

    private fun JsonNode.fieldLong(name: String): Long? {
        val value = path(name)
        return if (value.isLong || value.isInt) value.longValue() else null
    }

    private data class CachedModels(val models: List<AiModelDescriptor>, val expiresAt: Instant)

    private companion object {
        val SSE_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_CHAT_CONTENT_CHARS = 2_000_000
        const val MAX_ERROR_RESPONSE_CHARS = 8_000
    }
}
