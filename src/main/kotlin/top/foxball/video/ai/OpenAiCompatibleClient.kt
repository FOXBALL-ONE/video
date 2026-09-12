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

    /**
     * 获取 `/v1/models` 模型目录，并按连接档案缓存结果。
     * 缓存未命中时通过调用保护器执行请求，并对可恢复的网络、限流和 5xx 错误自动重试。
     */
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

    /**
     * 创建非流式聊天补全请求。
     * 非流式请求按提供商协议发送；流式请求统一使用 Responses API。
     */
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

    /**
     * 调用 OpenAI Responses API 并将其响应转换为统一的聊天结果模型。
     * 当前入口只接受文本消息，工具调用消息会被拒绝以避免协议语义不一致。
     */
    private fun createResponsesCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult> {
        val payload = responsesPayload(command, stream = false)
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

    /**
     * 调用 Chat Completions SSE 接口，将每个事件解析为统一的增量对象。
     * 收到 `[DONE]` 标记后结束流，并记录增量数量和终止信号。
     */
    override fun streamChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta> {
        validateCommandConnection(connection, command.providerConfigId)
        return streamResponsesCompletion(connection, command)
    }

    /** 调用 OpenAI Responses API 的 SSE 接口并转换为统一的文本增量事件。 */
    private fun streamResponsesCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta> {
        val payload = responsesPayload(command, stream = true)
        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        val deltaCount = AtomicInteger()
        logInvocationStarted(connection, AiOperation.CHAT, requestId, command.model, "protocol=responses stream=true message_count=${command.messages.size}")
        return invocationGuard.protectFlux(connection, AiOperation.CHAT) {
            transportFactory.client(connection.profile)
                .post()
                .uri(uriPolicy.endpoint(connection.baseUri, "responses"))
                .headers { headers -> applyHeaders(headers, connection, requestId) }
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(payload)
                .exchangeToFlux<AiChatCompletionDelta> { response ->
                    logUpstreamResponse(connection, AiOperation.CHAT, requestId, "responses", response)
                    if (!response.statusCode().is2xxSuccessful) errorFlux(response)
                    else response.bodyToFlux(SSE_TYPE)
                        .mapNotNull(ServerSentEvent<String>::data)
                        .takeWhile { it != "[DONE]" }
                        .mapNotNull(::parseResponsesDelta)
                }
                .onErrorMap(::mapTransportException)
                .doOnNext { deltaCount.incrementAndGet() }
        }.doOnError { exception ->
            logInvocationFailed(connection, AiOperation.CHAT, requestId, startedAt, exception, "protocol=responses stream=true delta_count=${deltaCount.get()}")
        }.doFinally { signal ->
            if (signal != reactor.core.publisher.SignalType.ON_ERROR) {
                logInvocationFinished(
                    connection,
                    AiOperation.CHAT,
                    requestId,
                    startedAt,
                    "protocol=responses stream=true signal=${signal.name} delta_count=${deltaCount.get()}",
                )
            }
        }
    }

    /** 校验图像生成参数，构造 JSON 请求并解析上游返回的图像列表。 */
    override fun generateImage(
        connection: AiProviderConnection,
        command: AiImageGenerationCommand,
    ): Mono<AiImageGenerationResult> {
        validateCommandConnection(connection, command.providerConfigId)
        validateImageCommand(command.model, command.prompt, command.count, command.size, command.quality)
        val payload = linkedMapOf<String, Any>(
            "model" to command.model,
            "prompt" to command.prompt,
        ).apply {
            if (!connection.provider.equals("openai", ignoreCase = true)) {
                put("response_format", command.responseFormat.wireValue)
            }
            command.count?.let { put("n", it) }
            command.size?.let { put("size", normalizeImageSize(it)) }
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
            "mode=generation image_count=${command.count ?: 1} size=${safeLog(command.size ?: "default")} response_format=${command.responseFormat.wireValue}",
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

    /** 校验图片及蒙版大小后构造 multipart 请求，调用图像编辑接口。 */
    override fun editImage(
        connection: AiProviderConnection,
        command: AiImageEditCommand,
    ): Mono<AiImageGenerationResult> {
        validateCommandConnection(connection, command.providerConfigId)
        validateImageCommand(command.model, command.prompt, command.count, command.size, null)
        require(command.images.isNotEmpty()) { "至少需要一张待编辑图片" }
        command.images.forEach(::validateImage)
        command.mask?.let(::validateImage)
        val totalImageBytes = command.images.sumOf { it.bytes.size.toLong() } + (command.mask?.bytes?.size?.toLong() ?: 0L)
        if (totalImageBytes > MAX_TOTAL_EDIT_IMAGE_BYTES) throw AiConfigurationException("图像编辑输入总大小不能超过 50 MB")

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
        command.size?.let { multipart.part("size", normalizeImageSize(it)) }

        val requestId = invocationId(command.requestId)
        val startedAt = System.nanoTime()
        logInvocationStarted(
            connection,
            AiOperation.IMAGE,
            requestId,
            command.model,
            "mode=edit image_count=${command.images.size} image_bytes=$totalImageBytes mask=${command.mask != null} size=${safeLog(command.size ?: "default")} response_format=${command.responseFormat.wireValue} files=${safeLog(command.images.map { image -> "${image.filename}:${image.contentType}:${image.bytes.size}" }.joinToString(","))} mask_file=${safeLog(command.mask?.let { "${it.filename}:${it.contentType}:${it.bytes.size}" } ?: "-")}",
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

    /** 校验视频生成参数并创建 JSON 或 multipart 形式的异步视频任务。 */
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

    /** 根据视频任务 ID 查询状态，并将供应商状态名称映射为统一枚举。 */
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

    /** 下载视频任务的指定内容变体，并包装为带 MIME 类型和文件名的结果。 */
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

    /** 构造并执行 JSON GET/POST 请求，统一处理响应解析和传输异常映射。 */
    private fun exchangeJson(
        connection: AiProviderConnection,
        resource: String,
        operation: AiOperation,
        payload: Any? = null,
        requestId: String? = null,
    ): Mono<JsonNode> {
        val uri = uriPolicy.endpoint(connection.baseUri, resource)
        log.debug(
            "AI HTTP request operation={} provider_config_id={} provider={} method={} uri={} request_id={} content_type={} payload={}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            if (payload == null) "GET" else "POST",
            safeLog(uri.toString()),
            safeLog(requestId ?: "-"),
            if (payload == null) "-" else MediaType.APPLICATION_JSON_VALUE,
            safeLog(payloadSummary(payload)),
        )
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

    /** 将视频生成命令转换为无文件引用时使用的 JSON 请求体。 */
    private fun videoPayload(command: AiVideoGenerationCommand): Map<String, Any> = linkedMapOf(
        "model" to command.model,
        "prompt" to command.prompt,
        "size" to command.size,
        "seconds" to command.seconds.toString(),
    )

    /** 规范化调用追踪 ID，清理控制字符并在缺失时生成随机 UUID。 */
    private fun invocationId(requestId: String?): String =
        requestId?.trim()?.takeIf(String::isNotBlank)?.take(MAX_REQUEST_ID_CHARS)?.map { character ->
            if (character.isISOControl()) '_' else character
        }?.joinToString("") ?: UUID.randomUUID().toString()

    /** 记录一次模型调用开始事件，统一输出提供商、模型和请求 ID。 */
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

    /** 记录模型调用成功及耗时、结果规模等摘要信息。 */
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

    /** 记录模型调用失败，错误摘要不包含密钥、提示词或完整上游响应。 */
    private fun logInvocationFailed(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String,
        startedAt: Long,
        exception: Throwable,
        details: String = "",
    ) {
        val responseBody = when (exception) {
            is AiUpstreamAuthenticationException -> exception.responseContent
            is AiRateLimitException -> exception.responseContent
            is AiUpstreamException -> exception.responseContent
            is AiProtocolException -> exception.responseContent
            else -> null
        }
        log.warn(
            "AI model call failed operation={} provider_config_id={} provider={} request_id={} elapsed_ms={} error={} response_body={} {}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            safeLog(requestId),
            elapsedMillis(startedAt),
            errorSummary(exception),
            safeLog(responseBody ?: "-", MAX_ERROR_RESPONSE_CHARS),
            details,
        )
    }

    /** 记录流式调用正常结束或取消时的终止信号和耗时。 */
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

    /** 记录上游 HTTP 响应状态，仅保留资源名和安全截断后的标识。 */
    private fun logUpstreamResponse(
        connection: AiProviderConnection,
        operation: AiOperation,
        requestId: String?,
        resource: String,
        response: ClientResponse,
    ) {
        log.info(
            "AI upstream response operation={} provider_config_id={} provider={} endpoint=/v1/{} request_id={} status={} content_type={} content_length={} retry_after={} headers={}",
            operation,
            connection.providerConfigId,
            safeLog(connection.provider),
            resource,
            safeLog(requestId ?: "-"),
            response.statusCode().value(),
            response.headers().contentType().map(MediaType::toString).orElse("-"),
            response.headers().contentLength().orElse(-1L),
            safeLog(response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER) ?: "-"),
            safeLog(response.headers().asHttpHeaders().toSingleValueMap().entries
                .filter { entry -> !entry.key.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) && !entry.key.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
                .joinToString(",") { entry -> "${entry.key}=${entry.value}" }),
        )
    }

    /** 将单调时钟起点转换为本次调用已经历的毫秒数。 */
    private fun elapsedMillis(startedAt: Long): Long = Duration.ofNanos(System.nanoTime() - startedAt).toMillis()

    /** 清理日志中的控制字符并截断长度，降低日志注入和过量输出风险。 */
    private fun safeLog(value: String, maxChars: Int = MAX_LOG_FIELD_CHARS): String =
        value.map { character ->
            if (character.isISOControl() || character == '\u2028' || character == '\u2029') '_' else character
        }.joinToString("").take(maxChars)

    /** 将异常压缩为不泄露响应正文的类型和状态摘要。 */
    private fun errorSummary(exception: Throwable): String = when (exception) {
        is AiUpstreamAuthenticationException -> "${exception::class.simpleName}(status=${exception.statusCode})"
        is AiRateLimitException -> "${exception::class.simpleName}(status=429)"
        is AiUpstreamException -> "${exception::class.simpleName}(status=${exception.statusCode})"
        else -> exception::class.simpleName ?: "UnknownException"
    }

    /** 将成功响应解码为 JSON；非 2xx 响应转换为带状态码的领域异常。 */
    private fun jsonResponse(response: ClientResponse): Mono<JsonNode> {
        if (response.statusCode().is2xxSuccessful) {
            return response.bodyToMono(JsonNode::class.java)
                .switchIfEmpty(Mono.error(AiProtocolException("模型服务未返回 JSON 响应")))
        }
        val statusCode = response.statusCode().value()
        val retryAfter = response.headers().asHttpHeaders().getFirst(HttpHeaders.RETRY_AFTER)?.toLongOrNull()
        return response.bodyToMono(String::class.java).defaultIfEmpty("").flatMap { responseContent ->
            log.warn(
                "AI upstream error response status={} content_type={} content_length={} body={}",
                statusCode,
                response.headers().contentType().map(MediaType::toString).orElse("-"),
                response.headers().contentLength().orElse(-1L),
                safeLog(responseContent, MAX_ERROR_RESPONSE_CHARS),
            )
            val error: Throwable = when {
                statusCode == 401 || statusCode == 403 -> AiUpstreamAuthenticationException(statusCode, responseContent.take(MAX_ERROR_RESPONSE_CHARS))
                statusCode == 429 -> AiRateLimitException(retryAfter, responseContent.take(MAX_ERROR_RESPONSE_CHARS))
                else -> AiUpstreamException(statusCode, responseContent.take(MAX_ERROR_RESPONSE_CHARS))
            }
            Mono.error(error)
        }
    }

    /** 仅记录 JSON 请求的结构和规模，避免把提示词、消息正文等敏感内容写入日志。 */
    private fun payloadSummary(payload: Any?): String = when (payload) {
        null -> "-"
        is Map<*, *> -> payload.entries.joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
            val summary = when (value) {
                is String -> "string(length=${value.length})"
                is Collection<*> -> "collection(size=${value.size})"
                is Map<*, *> -> "object(keys=${value.keys.size})"
                else -> value?.toString() ?: "null"
            }
            "$key=$summary"
        }
        else -> payload::class.simpleName ?: "object"
    }

    /** 消费错误响应正文后，以协议异常结束流式结果。 */
    private fun errorFlux(response: ClientResponse): Flux<AiChatCompletionDelta> =
        jsonResponse(response).thenMany(Flux.error(AiProtocolException("模型服务流式响应格式错误")))

    /** 写入所有请求共享的认证、客户端标识和可选追踪请求头。 */
    private fun applyHeaders(headers: HttpHeaders, connection: AiProviderConnection, requestId: String?) {
        headers.setBearerAuth(connection.apiKey)
        headers.set(HttpHeaders.USER_AGENT, "video-ai-client")
        requestId?.takeIf { it.isNotBlank() }?.let { headers.set("X-Request-Id", it) }
    }

    /** 校验聊天命令并构造 Chat Completions JSON 请求体。 */
    private fun chatPayload(command: AiChatCompletionCommand, stream: Boolean): Map<String, Any> {
        require(command.model.isNotBlank()) { "模型 ID 不能为空" }
        require(command.messages.isNotEmpty()) { "对话消息不能为空" }
        if (command.messages.sumOf { it.content?.length ?: 0 } > MAX_CHAT_CONTENT_CHARS) {
            throw AiConfigurationException("对话内容超过 2 MB 限制")
        }
        val messages = ensureJsonInstruction(command.messages, command.responseFormat)
        return linkedMapOf<String, Any>(
            "model" to command.model,
            "messages" to messages.map(::chatMessagePayload),
            "stream" to stream,
        ).apply {
            command.temperature?.let { put("temperature", it) }
            command.maxCompletionTokens?.let { put("max_completion_tokens", it) }
            command.responseFormat?.let { format ->
                when (format) {
                    "json_object" -> put("response_format", mapOf("type" to "json_object"))
                    "json_schema" -> {
                        val schema = command.responseSchema ?: throw AiConfigurationException("json_schema 缺少输出 schema")
                        put(
                            "response_format",
                            mapOf(
                                "type" to "json_schema",
                                "json_schema" to mapOf("name" to "structured_output", "strict" to true, "schema" to schema),
                            ),
                        )
                    }
                    "text" -> Unit
                    else -> throw AiConfigurationException("不支持的响应格式")
                }
            }
            if (command.tools.isNotEmpty()) put("tools", command.tools.map(::toolPayload))
        }
    }

    /** 校验 Responses API 命令并构造普通或流式请求体。 */
    private fun responsesPayload(command: AiChatCompletionCommand, stream: Boolean): Map<String, Any> {
        require(command.model.isNotBlank()) { "模型 ID 不能为空" }
        require(command.messages.isNotEmpty()) { "对话消息不能为空" }
        if (command.messages.any { it.role == AiChatRole.TOOL || it.toolCalls.isNotEmpty() }) {
            throw AiConfigurationException("Responses API 暂不支持通过此入口提交工具调用")
        }
        val messages = ensureJsonInstruction(command.messages, command.responseFormat)
        return linkedMapOf<String, Any>(
            "model" to command.model,
            "input" to messages.map { message ->
                mapOf(
                    "role" to message.role.name.lowercase(),
                    "content" to listOf(mapOf("type" to "input_text", "text" to message.content.orEmpty())),
                )
            },
        ).apply {
            if (stream) put("stream", true)
            command.temperature?.let { put("temperature", it) }
            command.maxCompletionTokens?.let { put("max_output_tokens", it) }
            command.responseFormat?.let { format ->
                when (format) {
                    "json_object" -> put("text", mapOf("format" to mapOf("type" to "json_object")))
                    "json_schema" -> {
                        val schema = command.responseSchema ?: throw AiConfigurationException("json_schema 缺少输出 schema")
                        put(
                            "text",
                            mapOf(
                                "format" to mapOf(
                                    "type" to "json_schema",
                                    "name" to "structured_output",
                                    "strict" to true,
                                    "schema" to schema,
                                ),
                            ),
                        )
                    }
                    "text" -> Unit
                    else -> throw AiConfigurationException("不支持的响应格式")
                }
            }
        }
    }

    /** 结构化 JSON 输出要求输入消息明确提及 json；为历史或自定义提示词补齐该协议约束。 */
    private fun ensureJsonInstruction(messages: List<AiChatMessage>, responseFormat: String?): List<AiChatMessage> {
        if (responseFormat !in setOf("json_object", "json_schema") || messages.any { it.content?.contains("json") == true }) {
            return messages
        }
        val userIndex = messages.indexOfLast { it.role == AiChatRole.USER }
        if (userIndex < 0) return messages + AiChatMessage(AiChatRole.USER, "请以 json 格式输出结果。")
        return messages.toMutableList().also { normalized ->
            val message = normalized[userIndex]
            normalized[userIndex] = message.copy(content = "${message.content.orEmpty()}\n请以 json 格式输出结果。".trim())
        }
    }

    /** 将统一聊天消息模型转换为上游协议字段，并校验工具消息完整性。 */
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

    /** 将工具定义转换为 OpenAI function 工具结构并校验名称。 */
    private fun toolPayload(tool: AiChatToolDefinition): Map<String, Any> {
        if (tool.name.isBlank()) throw AiConfigurationException("工具名称不能为空")
        return mapOf("type" to "function", "function" to linkedMapOf<String, Any>("name" to tool.name, "parameters" to tool.parameters).apply {
            tool.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        })
    }

    /** 解析模型列表 JSON，过滤缺少 ID 的项目并按 ID 去重。 */
    private fun parseModels(response: JsonNode): List<AiModelDescriptor> {
        val data = response.path("data")
        if (!data.isArray) throw AiProtocolException("模型服务响应不符合 /v1/models 协议")
        return data.mapNotNull { item ->
            item.fieldString("id")?.let { id -> AiModelDescriptor(id, item.fieldString("owned_by")) }
        }.distinctBy(AiModelDescriptor::id)
    }

    /** 解析 Chat Completions 响应中的 choices、消息和 token 用量。 */
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

    /** 从 Responses API 的 output_text 或 output.message.content 提取文本结果。 */
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

    /** 将单个 choices 节点解析为统一选项，无法识别角色时返回 null。 */
    private fun parseChatChoice(choice: JsonNode): AiChatChoice? {
        val message = choice.path("message")
        val role = message.fieldString("role")?.uppercase()?.let { value -> AiChatRole.entries.firstOrNull { it.name == value } } ?: return null
        return AiChatChoice(
            index = choice.fieldInt("index") ?: 0,
            message = AiChatMessage(role, message.fieldString("content"), toolCalls = parseToolCalls(message.path("tool_calls"))),
            finishReason = choice.fieldString("finish_reason"),
        )
    }

    /** 解析 Responses API 语义化 SSE 事件，仅向上层发出文本增量和完成信号。 */
    private fun parseResponsesDelta(event: String): AiChatCompletionDelta? {
        val response = try {
            objectMapper.readTree(event)
        } catch (exception: Exception) {
            throw AiProtocolException("Responses API 返回了无法解析的 SSE 事件")
        }
        return when (response.fieldString("type")) {
            "response.output_text.delta" -> AiChatCompletionDelta(
                id = response.fieldString("item_id") ?: response.fieldString("response_id"),
                choiceIndex = 0,
                role = null,
                content = response.path("delta").takeIf { it.isString }?.stringValue(),
                toolCalls = emptyList(),
                finishReason = null,
            )
            "response.completed" -> {
                val completed = response.path("response")
                AiChatCompletionDelta(
                    id = completed.fieldString("id") ?: response.fieldString("response_id"),
                    choiceIndex = 0,
                    role = null,
                    content = null,
                    toolCalls = emptyList(),
                    finishReason = completed.fieldString("status") ?: "completed",
                )
            }
            "response.failed", "error" -> throw AiProtocolException(
                response.fieldString("message")
                    ?: response.path("error").fieldString("message")
                    ?: response.path("response").path("error").fieldString("message")
                    ?: "Responses API 流式响应失败",
            )
            else -> null
        }
    }

    /** 兼容 Chat Completions 与 Responses API 的 token 用量字段命名。 */
    private fun parseUsage(usage: JsonNode): AiTokenUsage = AiTokenUsage(
        inputTokens = usage.fieldInt("prompt_tokens") ?: usage.fieldInt("input_tokens"),
        outputTokens = usage.fieldInt("completion_tokens") ?: usage.fieldInt("output_tokens"),
        totalTokens = usage.fieldInt("total_tokens"),
    )

    /** 从消息或增量节点提取合法的函数工具调用，忽略缺少 ID 或名称的条目。 */
    private fun parseToolCalls(node: JsonNode): List<AiChatToolCall> {
        if (!node.isArray) return emptyList()
        return node.mapNotNull { call ->
            val function = call.path("function")
            val id = call.fieldString("id") ?: return@mapNotNull null
            val name = function.fieldString("name") ?: return@mapNotNull null
            AiChatToolCall(id, name, function.fieldString("arguments").orEmpty())
        }
    }

    /** 解析 OpenAI 及中转服务常见的图像响应结构，支持 URL、base64 和嵌套结果。 */
    private fun parseImages(response: JsonNode): AiImageGenerationResult {
        val upstreamError = response.path("error")
        if (upstreamError.isObject) {
            val message = upstreamError.fieldString("message") ?: "上游图像服务返回错误"
            val code = upstreamError.fieldString("code")
            val responseBody = response.toString().take(MAX_ERROR_RESPONSE_CHARS)
            log.warn(
                "图像响应包含上游错误：error_message={} error_code={} response_body={}",
                safeLog(message),
                safeLog(code ?: "-"),
                safeLog(responseBody, MAX_ERROR_RESPONSE_CHARS),
            )
            throw AiProtocolException("模型服务返回错误：${safeLog(message)}", responseBody)
        }
        val images = mutableListOf<AiGeneratedImage>()
        collectImageNodes(response, images, 0)
        if (images.isEmpty()) {
            log.warn(
                "图像响应未识别到图片：顶层字段={} 响应类型={} response_body={}",
                responseFieldNames(response),
                response.nodeType,
                safeLog(response.toString(), MAX_ERROR_RESPONSE_CHARS),
            )
            throw AiProtocolException("模型服务响应不包含图像数据", response.toString().take(MAX_ERROR_RESPONSE_CHARS))
        }
        return AiImageGenerationResult(
            createdAtEpochSeconds = response.fieldLong("created"),
            images = images,
        )
    }

    /** 在有限深度内查找图片容器，避免把提示词或错误文本误识别成图片。 */
    private fun collectImageNodes(node: JsonNode, output: MutableList<AiGeneratedImage>, depth: Int) {
        if (depth > MAX_IMAGE_RESPONSE_DEPTH || node.isMissingNode || node.isNull) return
        if (node.isArray) {
            node.forEach { item -> collectImageNodes(item, output, depth + 1) }
            return
        }
        if (node.isString) {
            val value = node.stringValue().trim()
            when {
                value.startsWith("http://") || value.startsWith("https://") -> output += AiGeneratedImage(url = value)
                value.startsWith("data:image/", ignoreCase = true) -> output += AiGeneratedImage(base64Data = value)
            }
            return
        }
        if (!node.isObject) return

        parseImageNode(node)?.let {
            output += it
            return
        }
        IMAGE_RESPONSE_CONTAINER_FIELDS.forEach { field ->
            val child = node.path(field)
            if (!child.isMissingNode && !child.isNull) collectImageNodes(child, output, depth + 1)
        }
    }

    /** 从单个条目读取 url、image_url、b64_json 及常见 base64 字段。 */
    private fun parseImageNode(node: JsonNode): AiGeneratedImage? {
        val nestedUrl = node.path("image_url").fieldString("url")
            ?: node.path("image").fieldString("url")
        val url = node.fieldString("url") ?: nestedUrl
        val base64 = listOf("b64_json", "base64", "base64_data", "image_base64", "data")
            .asSequence()
            .mapNotNull { field -> node.fieldString(field) }
            .firstOrNull { value -> !value.startsWith("http://") && !value.startsWith("https://") }
            ?: node.fieldString("image")?.takeIf { value -> value.startsWith("data:image/", ignoreCase = true) }
        if (url == null && base64 == null) return null
        return AiGeneratedImage(url, base64, node.fieldString("revised_prompt"))
    }

    private fun responseFieldNames(node: JsonNode): List<String> =
        IMAGE_RESPONSE_CONTAINER_FIELDS.filter { field ->
            val child = node.path(field)
            !child.isMissingNode && !child.isNull
        }

    /** 解析视频任务响应并兼容不同供应商的状态、时间和错误字段命名。 */
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

    /** 将内存中的图片字节包装为带原始文件名的 multipart 资源。 */
    private fun imageResource(image: AiImageInput): ByteArrayResource = object : ByteArrayResource(image.bytes) {
        /** 返回 multipart 部件应使用的原始文件名。 */
        override fun getFilename(): String = image.filename
    }

    /** 校验图像模型、提示词、数量、尺寸和质量等生成参数。 */
    private fun validateImageCommand(model: String, prompt: String, count: Int?, size: String?, quality: String?) {
        require(model.isNotBlank()) { "图像模型 ID 不能为空" }
        require(prompt.isNotBlank()) { "图像提示词不能为空" }
        if (count != null && count !in 1..10) throw AiConfigurationException("图像数量必须在 1 到 10 之间")
        size?.let(::normalizeImageSize)
        quality?.let { value ->
            if (value.length > MAX_IMAGE_OPTION_CHARS || value.any(Char::isISOControl)) {
                throw AiConfigurationException("图像质量参数不正确")
            }
        }
    }

    /** 清理并校验图像尺寸，统一处理 `1K`、`2K`、`auto` 和像素尺寸写法。 */
    private fun normalizeImageSize(value: String): String {
        val normalized = value.trim()
        if (normalized.isBlank() || normalized.length > MAX_IMAGE_OPTION_CHARS || normalized.any(Char::isISOControl)) {
            throw AiConfigurationException("图像尺寸参数不正确")
        }
        if (normalized.equals("2k", ignoreCase = true)) return "2K"
        if (normalized.equals("1k", ignoreCase = true)) return "1K"
        if (normalized.equals("auto", ignoreCase = true)) return "auto"
        if (!normalized.matches(IMAGE_DIMENSION_PATTERN)) throw AiConfigurationException("图像尺寸参数不正确")
        return normalized
    }

    /** 校验上传图片的文件名、MIME 类型、非空内容和单文件大小上限。 */
    private fun validateImage(image: AiImageInput) {
        if (image.filename.isBlank() || image.contentType.isBlank() || image.bytes.isEmpty()) throw AiConfigurationException("图像编辑输入不完整")
        if (!image.contentType.startsWith("image/")) throw AiConfigurationException("图像编辑仅支持图片文件")
        if (image.bytes.size > MAX_IMAGE_BYTES) throw AiConfigurationException("单张图像不能超过 20 MB")
    }

    /** 清理视频 ID 并拒绝空值、控制字符及路径分隔符，防止路径注入。 */
    private fun normalizeVideoId(videoId: String): String {
        val value = videoId.trim()
        if (value.isBlank() || value.length > 200 || value.any { it.isISOControl() } || value.contains('/')) {
            throw AiConfigurationException("视频 ID 不正确")
        }
        return value
    }

    /** 确认命令中的配置 ID 与实际解析出的连接一致，避免串用提供商。 */
    private fun validateCommandConnection(connection: AiProviderConnection, providerConfigId: Long) {
        if (connection.providerConfigId != providerConfigId) throw AiConfigurationException("AI 调用与提供商配置不匹配")
    }

    /** 将 WebClient 底层异常统一映射为应用层 AI 客户端异常类型。 */
    private fun mapTransportException(exception: Throwable): Throwable = when (exception) {
        is AiClientException -> exception
        is WebClientRequestException -> AiTransportException("无法连接模型服务", exception)
        else -> AiTransportException("模型服务调用失败", exception)
    }

    /** 读取并裁剪 JSON 字符串字段，空字符串按缺失处理。 */
    private fun JsonNode.fieldString(name: String): String? {
        val value = path(name)
        return if (value.isString) value.stringValue().trim().takeIf(String::isNotEmpty) else null
    }

    /** 读取 JSON 整数或长整数节点并转换为 Int。 */
    private fun JsonNode.fieldInt(name: String): Int? {
        val value = path(name)
        return if (value.isInt || value.isLong) value.intValue() else null
    }

    /** 读取 JSON 整数节点并转换为 Long。 */
    private fun JsonNode.fieldLong(name: String): Long? {
        val value = path(name)
        return if (value.isLong || value.isInt) value.longValue() else null
    }

    private data class CachedModels(val models: List<AiModelDescriptor>, val expiresAt: Instant)

    private companion object {
        val SSE_TYPE = object : ParameterizedTypeReference<ServerSentEvent<String>>() {}
        const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        const val MAX_TOTAL_EDIT_IMAGE_BYTES = 50L * 1024 * 1024
        const val MAX_IMAGE_RESPONSE_DEPTH = 4
        const val MAX_IMAGE_OPTION_CHARS = 30
        const val MAX_CHAT_CONTENT_CHARS = 2_000_000
        const val MAX_ERROR_RESPONSE_CHARS = 8_000
        const val MAX_REQUEST_ID_CHARS = 128
        const val MAX_LOG_FIELD_CHARS = 256
        val VIDEO_VARIANTS = setOf("video", "thumbnail", "spritesheet")
        val IMAGE_RESPONSE_CONTAINER_FIELDS = listOf(
            "data", "images", "output", "result", "results", "response", "content", "message"
        )
        val IMAGE_DIMENSION_PATTERN = Regex("^[1-9][0-9]{2,4}x[1-9][0-9]{2,4}$")
    }
}
