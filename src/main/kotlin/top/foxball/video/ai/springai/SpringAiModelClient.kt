package top.foxball.video.ai.springai
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.image.ImagePrompt
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiImageOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiChatCompletionCommand
import top.foxball.video.ai.AiChatCompletionDelta
import top.foxball.video.ai.AiChatCompletionResult
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiChatChoice
import top.foxball.video.ai.AiChatToolCall
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiGeneratedImage
import top.foxball.video.ai.AiImageEditCommand
import top.foxball.video.ai.AiImageGenerationCommand
import top.foxball.video.ai.AiImageGenerationResult
import top.foxball.video.ai.AiInvocationGuard
import top.foxball.video.ai.AiModelClient
import top.foxball.video.ai.AiModelDescriptor
import top.foxball.video.ai.AiOperation
import top.foxball.video.ai.AiProtocolException
import top.foxball.video.ai.AiProviderConnection
import top.foxball.video.ai.AiTokenUsage
import top.foxball.video.ai.AiVideoContent
import top.foxball.video.ai.AiVideoGenerationCommand
import top.foxball.video.ai.AiVideoGenerationResult
import top.foxball.video.ai.OpenAiCompatibleClient

/**
 * 基于 Spring AI 的模型客户端。
 *
 * 对话（含流式）与图像生成改由 Spring AI 的 OpenAI 模型实现承担，从而获得统一的
 * 提示词模板、工具调用（技能与 MCP）与结构化输出能力。Spring AI 未覆盖的能力
 * ——模型目录、图像编辑、视频生成与下载——仍委托给 [OpenAiCompatibleClient]。
 */
@Component
@Primary
class SpringAiModelClient(
    private val modelFactory: SpringAiModelFactory,
    private val exceptionMapper: SpringAiExceptionMapper,
    private val invocationGuard: AiInvocationGuard,
    private val openAiCompatibleClient: OpenAiCompatibleClient,
    private val objectMapper: ObjectMapper,
) : AiModelClient {
    override fun listModels(connection: AiProviderConnection): Mono<List<AiModelDescriptor>> =
        openAiCompatibleClient.listModels(connection)

    override fun createChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult> = invocationGuard.protect(connection, AiOperation.CHAT) {
        Mono.fromCallable { executeChatCompletion(connection, command) }
            .subscribeOn(Schedulers.boundedElastic())
    }

    override fun streamChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta> = invocationGuard.protectFlux(connection, AiOperation.CHAT) {
        val prompt = Prompt(SpringAiMessageMapper.toSpringMessages(ensureJsonInstruction(command).messages), chatOptions(command))
        modelFactory.chatModel(connection, command.model)
            .stream(prompt)
            .mapNotNull(::toDelta)
            .onErrorMap(exceptionMapper::translate)
    }

    override fun generateImage(
        connection: AiProviderConnection,
        command: AiImageGenerationCommand,
    ): Mono<AiImageGenerationResult> = invocationGuard.protect(connection, AiOperation.IMAGE) {
        Mono.fromCallable { executeImageGeneration(connection, command) }
            .subscribeOn(Schedulers.boundedElastic())
    }

    override fun editImage(
        connection: AiProviderConnection,
        command: AiImageEditCommand,
    ): Mono<AiImageGenerationResult> = openAiCompatibleClient.editImage(connection, command)

    override fun createVideo(
        connection: AiProviderConnection,
        command: AiVideoGenerationCommand,
    ): Mono<AiVideoGenerationResult> = openAiCompatibleClient.createVideo(connection, command)

    override fun getVideo(
        connection: AiProviderConnection,
        videoId: String,
        requestId: String?,
    ): Mono<AiVideoGenerationResult> = openAiCompatibleClient.getVideo(connection, videoId, requestId)

    override fun downloadVideo(
        connection: AiProviderConnection,
        videoId: String,
        variant: String?,
        requestId: String?,
    ): Mono<AiVideoContent> = openAiCompatibleClient.downloadVideo(connection, videoId, variant, requestId)

    /** 执行一次非流式对话，并把 Spring AI 响应折叠为项目领域模型。 */
    private fun executeChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): AiChatCompletionResult {
        val prompt = Prompt(SpringAiMessageMapper.toSpringMessages(ensureJsonInstruction(command).messages), chatOptions(command))
        val response = try {
            modelFactory.chatModel(connection, command.model).call(prompt)
        } catch (exception: Exception) {
            throw exceptionMapper.translate(exception)
        }
        return SpringAiMessageMapper.toCompletionResult(response, command.model)
    }

    /** 调用图像生成接口，返回上游给出的图片 URL 或 base64 数据。 */
    private fun executeImageGeneration(
        connection: AiProviderConnection,
        command: AiImageGenerationCommand,
    ): AiImageGenerationResult {
        val options = OpenAiImageOptions.builder()
            .model(command.model)
            .n(command.count)
            .size(command.size)
            .quality(command.quality)
            .style(command.style)
            .apply {
                // OpenAI 官方图像接口不接受 response_format，仅第三方兼容服务需要显式声明。
                if (!connection.provider.equals("openai", ignoreCase = true)) {
                    responseFormat(command.responseFormat.wireValue)
                }
            }
            .build()
        val response = try {
            modelFactory.imageModel(connection, command.model).call(ImagePrompt(command.prompt, options))
        } catch (exception: Exception) {
            throw exceptionMapper.translate(exception)
        }
        val images = response.results.map { generation ->
            val image = generation.output
            AiGeneratedImage(url = image.url, base64Data = image.b64Json)
        }
        if (images.isEmpty()) throw AiProtocolException("模型服务响应不包含图像数据")
        return AiImageGenerationResult(createdAtEpochSeconds = null, images = images)
    }

    /**
     * 组装单次对话的选项；模型、采样参数与结构化输出格式按调用方命令覆盖模型默认值。
     * 地址、密钥、代理与超时属于连接档案，已在 [SpringAiModelFactory] 构造模型时写入。
     */
    private fun chatOptions(command: AiChatCompletionCommand): OpenAiChatOptions {
        val builder = OpenAiChatOptions.builder()
            .model(command.model)
            .temperature(command.temperature)
            .maxCompletionTokens(command.maxCompletionTokens)
        command.responseFormat?.let { format ->
            when (format) {
                "json_object" -> builder.responseFormat(
                    OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build(),
                )
                "json_schema" -> {
                    val schema = command.responseSchema?.let { objectMapper.writeValueAsString(it) }
                        ?: throw AiConfigurationException("json_schema 缺少输出 schema")
                    builder.responseFormat(
                        OpenAiChatModel.ResponseFormat.builder()
                            .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                            .jsonSchema(schema)
                            .strict(true)
                            .build(),
                    )
                }
                "text" -> Unit
                else -> throw AiConfigurationException("不支持的响应格式")
            }
        }
        if (command.tools.isNotEmpty()) {
            builder.toolCallbacks(command.tools.map(::toToolCallback))
        }
        return builder.build()
    }

    /** 项目工具定义只描述签名，执行仍由调用方负责；这里提供不可调用的占位实现。 */
    private fun toToolCallback(definition: top.foxball.video.ai.AiChatToolDefinition): ToolCallback = object : ToolCallback {
        private val toolDefinition: ToolDefinition = DefaultToolDefinition.builder()
            .name(definition.name)
            .description(definition.description.orEmpty())
            .inputSchema(objectMapper.writeValueAsString(definition.parameters))
            .build()

        override fun getToolDefinition(): ToolDefinition = toolDefinition

        override fun call(toolInput: String): String =
            throw AiProtocolException("对话客户端不执行工具调用：${definition.name}")
    }

    private fun toDelta(response: ChatResponse): AiChatCompletionDelta? {
        val generation = response.result ?: return null
        val message = generation.output
        val content = message.text.takeIf { !it.isNullOrEmpty() }
        val toolCalls = message.toolCalls.map { AiChatToolCall(it.id(), it.name(), it.arguments()) }
        val finishReason = generation.metadata.finishReason
        if (content == null && toolCalls.isEmpty() && finishReason == null) return null
        return AiChatCompletionDelta(
            id = response.metadata.id,
            choiceIndex = 0,
            role = AiChatRole.ASSISTANT,
            content = content,
            toolCalls = toolCalls,
            finishReason = finishReason,
        )
    }

    /**
     * 部分兼容服务忽略 response_format，需要同时给出文本指令。
     * 与自研客户端保持相同规则：消息中已出现 json 字样时不再追加。
     */
    private fun ensureJsonInstruction(command: AiChatCompletionCommand): AiChatCompletionCommand {
        if (command.responseFormat !in setOf("json_object", "json_schema")) return command
        if (command.messages.any { it.content?.contains("json", ignoreCase = true) == true }) return command
        val instruction = AiChatMessage(AiChatRole.USER, content = "请以 json 格式输出结果。")
        return command.copy(messages = command.messages + instruction)
    }
}
