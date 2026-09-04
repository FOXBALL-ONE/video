package top.foxball.video.ai

import org.slf4j.LoggerFactory
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private val log = LoggerFactory.getLogger(OpenAiCompatibleClient::class.java)

    override fun listModels(connection: AiProviderConnection): Mono<List<AiModelDescriptor>> {
        val cacheKey = "${connection.providerConfigId ?: connection.baseUri}|${connection.profile.name}"
        val cached = modelCatalog[cacheKey]
        if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
            log.debug(
                "AI model call cache hit operation={} provider_config_id={} provider={} model_count={}",
                AiOperation.MODEL_LIST,
                connection.providerConfigId,
                safeLog(connection.provider),
                cached.models.size,
            )
            return Mono.just(cached.models)
        }

        val requestId = invocationId(null)
        val startedAt = System.nanoTime()
        logInvocationStarted(connection, AiOperation.MODEL_LIST, requestId, null, "")
        return invocationGuard.protect(connection, AiOperation.MODEL_LIST) {
            exchangeJson(connection, "models", AiOperation.MODEL_LIST, requestId = requestId)
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
                        }
                        .doBeforeRetry { signal ->
                            log.warn(
                                "AI model call retry operation={} provider_config_id={} request_id={} attempt={} reason={}",
                                AiOperation.MODEL_LIST,
                                connection.providerConfigId,
                                requestId,
                                signal.totalRetries() + 1,
                                errorSummary(signal.failure()),
                            )
                        },
                )
                .doOnNext { models ->
                    modelCatalog[cacheKey] = CachedModels(models, Instant.now().plus(properties.modelCatalogTtl))
                }
        }.doOnSuccess { models ->
            logInvocationSucceeded(connection, AiOperation.MODEL_LIST, requestId, startedAt, "model_count=${models?.size ?: 0}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.MODEL_LIST, requestId, startedAt, exception)
        }
    }

    override fun createChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult> {
        validateCommandConnection(connection, command.providerConfigId)
        if (connection.provider.equals("openai", ignoreCase = true)) {
            return createResponsesCompletion(connection, command)
        }
        val payload = chatPayload(command, stream = false)
        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(
            connection,
            AiOperation.CHAT,
            requestId,
            command.model,
            "stream=false message_count=${command.messages.size} input_chars=${command.messages.sumOf { it.content?.length ?: 0 }} tool_count=${command.tools.size}",
        )
        return invocationGuard.protect(connection, AiOperation.CHAT) {
            exchangeJson(connection, "chat/completions", AiOperation.CHAT, payload, requestId)
                .map(::parseChatCompletion)
        }.doOnSuccess { result ->
            val usage = result?.usage
            logInvocationSucceeded(
                connection,
                AiOperation.CHAT,
                requestId,
                startedAt,
                "stream=false choice_count=${result?.choices?.size ?: 0} input_tokens=${usage?.inputTokens ?: "-"} output_tokens=${usage?.outputTokens ?: "-"} total_tokens=${usage?.totalTokens ?: "-"}",
            )
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.CHAT, requestId, startedAt, exception)
        }
    }

    /** OpenAI 官方文本模型使用 Responses API；兼容供应商保留 Chat Completions 路径。 */
    private fun createResponsesCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult> {
        require(command.model.isNotBlank()) { "模型 ID 不能为空" }
        require(command.messages.isNotEmpty()) { "对话消息不能为空" }
        if (command.messages.any { it.role == AiChatRole.TOOL || it.toolCalls.isNotEmpty() }) {
            throw AiConfigurationException("Responses API 暂不支持通过此入口提交工具调用")
        }
        val payload = linkedMapOf<String, Any>(
            "model" to command.model,
            "input" to command.messages.map { message ->
                mapOf(
                    "role" to message.role.name.lowercase(),
                    "content" to listOf(mapOf("type" to "input_text", "text" to message.content.orEmpty())),
                )
            },
        ).apply {
            command.temperature?.let { put("temperature", it) }
            command.maxCompletionTokens?.let { put("max_output_tokens", it) }
        }
        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(connection, AiOperation.CHAT, requestId, command.model, "protocol=responses message_count=${command.messages.size}")
        return invocationGuard.protect(connection, AiOperation.CHAT) {
            exchangeJson(connection, "responses", AiOperation.CHAT, payload, requestId)
                .map(::parseResponsesCompletion)
        }.doOnSuccess { result ->
            logInvocationSucceeded(connection, AiOperation.CHAT, requestId, startedAt, "protocol=responses output_chars=${result?.primaryChoice?.message?.content?.length ?: 0}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.CHAT, requestId, startedAt, exception, "protocol=responses")
        }
    }

    override fun streamChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta> {
        validateCommandConnection(connection, command.providerConfigId)
        val payload = chatPayload(command, stream = true)
        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        val deltaCount = AtomicInteger()
        logInvocationStarted(
            connection,
            AiOperation.CHAT,
            requestId,
            command.model,
            "stream=true message_count=${command.messages.size} input_chars=${command.messages.sumOf { it.content?.length ?: 0 }} tool_count=${command.tools.size}",
        )
        return invocationGuard.protectFlux(connection, AiOperation.CHAT) {
            val uri = uriPolicy.endpoint(connection.baseUri, "chat/completions")
            transportFactory.client(connection.profile)
                .post()
                .uri(uri)
                .headers { headers -> applyHeaders(headers, connection, requestId) }
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .exchangeToFlux<AiChatCompletionDelta> { response ->
                    logUpstreamResponse(connection, AiOperation.CHAT, requestId, "chat/completions", response)
                    if (!response.statusCode().is2xxSuccessful) errorFlux(response)
                    else response.bodyToFlux(SSE_TYPE)
                        .mapNotNull(ServerSentEvent<String>::data)
                        .takeWhile { it != "[DONE]" }
                        .map(::parseChatDelta)
                }
                .onErrorMap(::mapTransportException)
                .doOnNext { deltaCount.incrementAndGet() }
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.CHAT, requestId, startedAt, exception, "stream=true delta_count=${deltaCount.get()}")
        }.doFinally { signal ->
            if (signal != reactor.core.publisher.SignalType.ON_ERROR) {
                logInvocationFinished(
                    connection,
                    AiOperation.CHAT,
                    requestId,
                    startedAt,
                    "stream=true signal=${signal.name} delta_count=${deltaCount.get()}",
                )
            }
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
        ).apply {
            if (!connection.provider.equals("openai", ignoreCase = true)) {
                put("response_format", command.responseFormat.wireValue)
            }
            command.count?.let { put("n", it) }
            command.size?.let { put("size", it) }
            command.quality?.let { put("quality", it) }
            command.style?.let { put("style", it) }
        }
        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(
            connection,
            AiOperation.IMAGE,
            requestId,
            command.model,
            "mode=generation image_count=${command.count ?: 1} response_format=${command.responseFormat.wireValue}",
        )
        return invocationGuard.protect(connection, AiOperation.IMAGE) {
            exchangeJson(connection, "images/generations", AiOperation.IMAGE, payload, requestId)
                .map(::parseImages)
        }.doOnSuccess { result ->
            logInvocationSucceeded(connection, AiOperation.IMAGE, requestId, startedAt, "mode=generation image_count=${result?.images?.size ?: 0}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.IMAGE, requestId, startedAt, exception, "mode=generation")
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
        if (!connection.provider.equals("openai", ignoreCase = true)) {
            multipart.part("response_format", command.responseFormat.wireValue)
        }
        command.images.forEach { image ->
            multipart.part("image[]", imageResource(image)).contentType(MediaType.parseMediaType(image.contentType))
        }
        command.mask?.let { mask ->
            multipart.part("mask", imageResource(mask)).contentType(MediaType.parseMediaType(mask.contentType))
        }
        command.count?.let { multipart.part("n", it.toString()) }
        command.size?.let { multipart.part("size", it) }

        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(
            connection,
            AiOperation.IMAGE,
            requestId,
            command.model,
            "mode=edit image_count=${command.images.size} image_bytes=${command.images.sumOf { it.bytes.size.toLong() }} mask=${command.mask != null} response_format=${command.responseFormat.wireValue}",
        )
        return invocationGuard.protect(connection, AiOperation.IMAGE) {
            val uri = uriPolicy.endpoint(connection.baseUri, "images/edits")
            transportFactory.client(connection.profile)
                .post()
                .uri(uri)
                .headers { headers -> applyHeaders(headers, connection, requestId) }
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchangeToMono { response ->
                    logUpstreamResponse(connection, AiOperation.IMAGE, requestId, "images/edits", response)
                    jsonResponse(response)
                }
                .map(::parseImages)
                .onErrorMap(::mapTransportException)
        }.doOnSuccess { result ->
            logInvocationSucceeded(connection, AiOperation.IMAGE, requestId, startedAt, "mode=edit image_count=${result?.images?.size ?: 0}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.IMAGE, requestId, startedAt, exception, "mode=edit")
        }
    }

    override fun createVideo(
        connection: AiProviderConnection,
        command: AiVideoGenerationCommand,
    ): Mono<AiVideoGenerationResult> {
        validateCommandConnection(connection, command.providerConfigId)
        require(command.model.isNotBlank()) { "视频模型 ID 不能为空" }
        require(command.prompt.isNotBlank()) { "视频提示词不能为空" }
        require(command.seconds in 1..20) { "视频时长必须在 1 到 20 秒之间" }
        require(command.size.matches(Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$"))) { "视频尺寸格式不正确" }
        command.inputReference?.let(::validateImage)

        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(
            connection,
            AiOperation.VIDEO,
            requestId,
            command.model,
            "mode=create size=${safeLog(command.size)} seconds=${command.seconds} input_reference=${command.inputReference != null}",
        )

        val request = transportFactory.client(connection.profile)
            .post()
            .uri(uriPolicy.endpoint(connection.baseUri, "videos"))
            .headers { headers -> applyHeaders(headers, connection, requestId) }
        val exchange = if (command.inputReference == null) {
            request
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(videoPayload(command))
                .exchangeToMono { response ->
                    logUpstreamResponse(connection, AiOperation.VIDEO, requestId, "videos", response)
                    jsonResponse(response)
                }
        } else {
            val multipart = MultipartBodyBuilder()
            multipart.part("model", command.model)
            multipart.part("prompt", command.prompt)
            multipart.part("size", command.size)
            multipart.part("seconds", command.seconds.toString())
            multipart.part("input_reference", imageResource(requireNotNull(command.inputReference)))
                .contentType(MediaType.parseMediaType(requireNotNull(command.inputReference).contentType))
            request
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart.build()))
                .exchangeToMono { response ->
                    logUpstreamResponse(connection, AiOperation.VIDEO, requestId, "videos", response)
                    jsonResponse(response)
                }
        }
        return invocationGuard.protect(connection, AiOperation.VIDEO) {
            exchange.map(::parseVideo)
                .onErrorMap(::mapTransportException)
        }.doOnSuccess { result ->
            logInvocationSucceeded(connection, AiOperation.VIDEO, requestId, startedAt, "mode=create video_id=${safeLog(result?.id ?: "-")} status=${result?.status}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.VIDEO, requestId, startedAt, exception, "mode=create")
        }
    }

    override fun getVideo(
        connection: AiProviderConnection,
        videoId: String,
        requestId: String?,
    ): Mono<AiVideoGenerationResult> {
        val normalizedId = normalizeVideoId(videoId)
        val requestIdValue = invocationId(requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(connection, AiOperation.VIDEO, requestIdValue, null, "mode=status video_id=${safeLog(normalizedId)}")
        return invocationGuard.protect(connection, AiOperation.VIDEO) {
            exchangeJson(connection, "videos/$normalizedId", AiOperation.VIDEO, requestId = requestIdValue)
                .map(::parseVideo)
        }.doOnSuccess { result ->
            logInvocationSucceeded(connection, AiOperation.VIDEO, requestIdValue, startedAt, "mode=status video_id=${safeLog(normalizedId)} status=${result?.status}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.VIDEO, requestIdValue, startedAt, exception, "mode=status")
        }
    }

    override fun downloadVideo(
        connection: AiProviderConnection,
        videoId: String,
        variant: String?,
        requestId: String?,
    ): Mono<AiVideoContent> {
        val normalizedId = normalizeVideoId(videoId)
        val normalizedVariant = variant?.trim()?.takeIf(String::isNotBlank)?.also {
            if (it !in VIDEO_VARIANTS) throw AiConfigurationException("不支持的视频内容类型")
        }
        val requestIdValue = invocationId(requestId)
        val startedAt = System.nanoTime()
        val endpoint = uriPolicy.endpoint(connection.baseUri, "videos/$normalizedId/content")
        val uri = if (normalizedVariant == null) endpoint else java.net.URI(
            endpoint.scheme,
            endpoint.authority,
            endpoint.path,
            "variant=${java.net.URLEncoder.encode(normalizedVariant, Charsets.UTF_8)}",
            null,
        )
        logInvocationStarted(connection, AiOperation.VIDEO, requestIdValue, null, "mode=content video_id=${safeLog(normalizedId)} variant=${safeLog(normalizedVariant ?: "video")}")
        return invocationGuard.protect(connection, AiOperation.VIDEO) {
            transportFactory.client(connection.profile)
                .get()
                .uri(uri)
                .headers { headers -> applyHeaders(headers, connection, requestIdValue) }
                .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON)
                .exchangeToMono { response ->
                    logUpstreamResponse(connection, AiOperation.VIDEO, requestIdValue, "videos/$normalizedId/content", response)
                    if (!response.statusCode().is2xxSuccessful) {
                        jsonResponse(response).then(Mono.error(AiProtocolException("视频内容下载失败")))
                    } else {
                        response.bodyToMono(ByteArray::class.java).map { bytes ->
                            val contentType = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM).toString()
                            AiVideoContent(bytes, contentType, "$normalizedId.mp4")
                        }
                    }
                }
                .onErrorMap(::mapTransportException)
        }.doOnSuccess { result ->
            logInvocationSucceeded(connection, AiOperation.VIDEO, requestIdValue, startedAt, "mode=content bytes=${result?.bytes?.size ?: 0}")
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.VIDEO, requestIdValue, startedAt, exception, "mode=content")
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
            .exchangeToMono { response ->
                logUpstreamResponse(connection, operation, requestId, resource, response)
                jsonResponse(response)
            }
            .onErrorMap(::mapTransportException)
    }

    private fun videoPayload(command: AiVideoGenerationCommand): Map<String, Any> = linkedMapOf(
        "model" to command.model,
        "prompt" to command.prompt,
        "size" to command.size,
        "seconds" to command.seconds.toString(),
    )

    private fun invocationId(requestId: String?): String =
        requestId?.trim()?.takeIf(String::isNotBlank)?.take(MAX_REQUEST_ID_CHARS)?.map { character ->
            if (character.isISOControl()) '_' else character
        }?.joinToString("") ?: UUID.randomUUID().toString()

    private fun logInvocationStarted(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String,
        model: String?,
        details: String,
    ) {
        log.info(
            "AI model call started operation={} provider_config_id={} provider={} model={} request_id={} {}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            safeLog(model ?: "-"),
            safeLog(requestId),
            details,
        )
    }

    private fun logInvocationSucceeded(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String,
        startedAt: Long,
        details: String,
    ) {
        log.info(
            "AI model call succeeded operation={} provider_config_id={} provider={} request_id={} elapsed_ms={} {}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            safeLog(requestId),
            elapsedMillis(startedAt),
            details,
        )
    }

    private fun logInvocationFailed(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String,
        startedAt: Long,
        exception: Throwable,
        details: String = "",
    ) {
        log.warn(
            "AI model call failed operation={} provider_config_id={} provider={} request_id={} elapsed_ms={} error={} {}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            safeLog(requestId),
            elapsedMillis(startedAt),
            errorSummary(exception),
            details,
        )
    }

    private fun logInvocationFinished(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String,
        startedAt: Long,
        details: String,
    ) {
        log.info(
            "AI model call finished operation={} provider_config_id={} provider={} request_id={} elapsed_ms={} {}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            safeLog(requestId),
            elapsedMillis(startedAt),
            details,
        )
    }

    private fun logUpstreamResponse(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String?,
        resource: String,
        response: ClientResponse,
    ) {
        log.info(
            "AI upstream response operation={} provider_config_id={} provider={} endpoint=/v1/{} request_id={} status={}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            resource,
            safeLog(requestId ?: "-"),
            response.statusCode().value(),
        )
    }

    private fun elapsedMillis(startedAt: Long): Long = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    private fun safeLog(value: String, maxChars: Int = MAX_LOG_FIELD_CHARS): String =
        value.map { character ->
            if (character.isISOControl() || character == '\u2028' || character == '\u2029') '_' else character
        }.joinToString("").take(maxChars)

    private fun errorSummary(exception: Throwable): String = when (exception) {
        is AiUpstreamAuthenticationException -> "${exception::class.simpleName}(status=${exception.statusCode})"
        is AiRateLimitException -> "${exception::class.simpleName}(status=429)"
        is AiUpstreamException -> "${exception::class.simpleName}(status=${exception.statusCode})"
        else -> exception::class.simpleName ?: "UnknownException"
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

    private fun parseResponsesCompletion(response: JsonNode): AiChatCompletionResult {
        val content = response.fieldString("output_text") ?: response.path("output")
            .firstOrNull { item -> item.fieldString("type") == "message" }
            ?.path("content")
            ?.mapNotNull { item -> item.fieldString("text") ?: item.fieldString("output_text") }
            ?.joinToString("")
            ?.takeIf(String::isNotBlank)
            ?: throw AiProtocolException("Responses API 未返回文本内容")
        return AiChatCompletionResult(
            id = response.fieldString("id"),
            model = response.fieldString("model"),
            choices = listOf(
                AiChatChoice(
                    index = 0,
                    message = AiChatMessage(AiChatRole.ASSISTANT, content),
                    finishReason = response.fieldString("status"),
                ),
            ),
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

    private fun parseVideo(response: JsonNode): AiVideoGenerationResult {
        val id = response.fieldString("id") ?: throw AiProtocolException("视频服务响应不包含视频 ID")
        val statusValue = response.fieldString("status")?.uppercase() ?: "UNKNOWN"
        val status = when (statusValue) {
            "QUEUED", "PENDING" -> AiVideoStatus.QUEUED
            "IN_PROGRESS", "PROCESSING", "RUNNING" -> AiVideoStatus.IN_PROGRESS
            "COMPLETED", "SUCCEEDED", "SUCCESS" -> AiVideoStatus.COMPLETED
            "FAILED", "ERROR" -> AiVideoStatus.FAILED
            "CANCELLED", "CANCELED" -> AiVideoStatus.CANCELLED
            else -> AiVideoStatus.UNKNOWN
        }
        val error = response.path("error")
        return AiVideoGenerationResult(
            id = id,
            model = response.fieldString("model"),
            status = status,
            progress = response.fieldInt("progress")?.coerceIn(0, 100),
            createdAtEpochSeconds = response.fieldLong("created_at"),
            completedAtEpochSeconds = response.fieldLong("completed_at"),
            errorCode = error.fieldString("code") ?: response.fieldString("error_code"),
            errorMessage = error.fieldString("message") ?: response.fieldString("error_message"),
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

    private fun normalizeVideoId(videoId: String): String {
        val value = videoId.trim()
        if (value.isBlank() || value.length > 200 || value.any { it.isISOControl() } || value.contains('/')) {
            throw AiConfigurationException("视频 ID 不正确")
        }
        return value
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
        const val MAX_REQUEST_ID_CHARS = 128
        const val MAX_LOG_FIELD_CHARS = 256
        val VIDEO_VARIANTS = setOf("video", "thumbnail", "spritesheet")
    }
}
