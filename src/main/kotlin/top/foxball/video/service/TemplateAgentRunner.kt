package top.foxball.video.service

import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingManager
import org.springframework.ai.model.tool.DefaultToolCallingManager
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import top.foxball.video.ai.AiChatCompletionResult
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiImageGenerationCommand
import top.foxball.video.ai.AiImageGenerationResult
import top.foxball.video.ai.AiImageResponseFormat
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.ai.AiProtocolException
import top.foxball.video.ai.springai.McpToolRegistrar
import top.foxball.video.ai.springai.SpringAiMessageMapper

/** 模板产出形态：纯文本、结构化 JSON 或直接生成图像。 */
enum class TemplateOutputMode { TEXT, JSON, IMAGE }

/** 图像提示词来源：由模型基于技能产出的文字，或模板渲染后的消息正文。 */
enum class TemplateImagePromptSource { MODEL_OUTPUT, RENDERED_MESSAGE }

/**
 * 模板执行器。
 *
 * 一次执行包含：解析模板版本引用的技能与工具、把技能说明注入 system 通道、装配内置工具与 MCP
 * 工具、按生成策略构造模型选项、驱动工具调用循环，最后返回文本产出与实际使用的工具集合。
 *
 * 工具循环使用 Spring AI 的 [ToolCallingManager]，因此每次工具调用的结果都会回填到对话历史，
 * 模型可以基于工具结果继续推理；循环轮数有上限，避免模型陷入无限工具调用。
 */
@Service
class TemplateAgentRunner(
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: TemplateToolRegistry,
    private val mcpToolRegistrar: McpToolRegistrar,
    private val schemaValidator: TemplateSchemaValidator,
    private val aiInvocationService: AiInvocationService,
) {
    /** 一次模板对话的执行参数。 */
    data class ChatRequest(
        val template: TemplateCatalogService.ResolvedTemplate,
        val rendered: TemplateRenderer.RenderedTemplate,
        val providerConfigId: Long,
        val model: String,
        val requestId: String,
        val additionalSkillKeys: List<String> = emptyList(),
        val additionalMcpServers: List<Pair<String, List<String>>> = emptyList(),
    )

    /** 模板对话的执行结果，包含审计所需的统计信息。 */
    data class ChatOutcome(
        val text: String,
        val id: String?,
        val model: String,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val toolCallCount: Int,
        val skillKeys: List<String>,
        val mcpServerKeys: List<String>,
        val attemptRounds: Int,
    )

    /** 图像生成参数，来自模板版本的生成策略。 */
    data class ImageSettings(
        val size: String?,
        val count: Int?,
        val quality: String?,
        val style: String?,
    )

    /** 执行模板对话，装配技能、内置工具与 MCP 工具，并驱动工具调用循环。 */
    fun chat(request: ChatRequest): ChatOutcome {
        val version = request.template.version
        val skillKeys = (schemaValidator.skillKeys(version.skillsJson) + request.additionalSkillKeys).distinct()
        val skillSpecs = skillRegistry.resolveSpecs(skillKeys)
        // MCP 绑定来自模板版本与技能声明的并集：同一服务端取并集白名单，空表示该服务端全部工具。
        val bindings = linkedMapOf<String, MutableSet<String>>()
        schemaValidator.mcpServerBindings(version.mcpServersJson).forEach { (key, tools) ->
            bindings.getOrPut(key) { linkedSetOf() }.addAll(tools)
        }
        request.additionalMcpServers.forEach { (key, tools) ->
            bindings.getOrPut(key) { linkedSetOf() }.addAll(tools)
        }
        skillSpecs.forEach { spec -> spec.mcpServers.forEach { key -> bindings.getOrPut(key) { linkedSetOf() } } }
        val mcpServerKeys = bindings.keys.toList()
        val builtinToolNames = (schemaValidator.toolNames(version.toolsJson) + skillSpecs.flatMap { it.tools }).distinct()
        val toolCallbacks = toolRegistry.callbacksFor(builtinToolNames) +
            bindings.map { (key, tools) -> mcpToolRegistrar.toolCallbacks(key, tools.toList()) }.flatten()
        val messages = buildList {
            skillRegistry.systemBlock(skillSpecs)?.let { add(AiChatMessage(AiChatRole.SYSTEM, it)) }
            addAll(request.rendered.messages)
        }
        val options = chatOptions(version, request.model, toolCallbacks)
        val loop = aiInvocationService.useChatModel(request.providerConfigId, request.model) { model ->
            driveToolLoop(model, messages, options)
        }.block() ?: throw AiProtocolException("模型服务未返回结果")
        val text = loop.response.result?.output?.text.orEmpty()
        if (text.isBlank()) throw AiProtocolException("模型未返回可用内容")
        val usage = SpringAiMessageMapper.toUsage(loop.response)
        return ChatOutcome(
            text = text,
            id = loop.response.metadata.id,
            model = loop.response.metadata.model ?: request.model,
            inputTokens = usage?.inputTokens,
            outputTokens = usage?.outputTokens,
            toolCallCount = loop.toolCallCount,
            skillKeys = skillSpecs.map { it.key },
            mcpServerKeys = mcpServerKeys,
            attemptRounds = loop.rounds,
        )
    }

    /** 读取模板版本的产出形态；未显式声明时按响应格式推断。 */
    fun outputMode(version: top.foxball.video.entity.jdbc.AiTemplateVersion): TemplateOutputMode {
        val policy = policyOf(version)
        policy.path("output_mode").stringValue(null)?.let { mode ->
            return when (mode) {
                "image" -> TemplateOutputMode.IMAGE
                "json" -> TemplateOutputMode.JSON
                else -> TemplateOutputMode.TEXT
            }
        }
        return when (policy.path("response_format").stringValue(null)) {
            "json_object", "json_schema" -> TemplateOutputMode.JSON
            else -> TemplateOutputMode.TEXT
        }
    }

    /** 读取图像提示词来源；默认使用模型产出，便于技能先改写提示词。 */
    fun imagePromptSource(version: top.foxball.video.entity.jdbc.AiTemplateVersion): TemplateImagePromptSource =
        when (policyOf(version).path("image").path("prompt_source").stringValue(null)) {
            "rendered_message" -> TemplateImagePromptSource.RENDERED_MESSAGE
            else -> TemplateImagePromptSource.MODEL_OUTPUT
        }

    /** 读取图像生成参数；策略中未声明的字段由调用方决定默认值。 */
    fun imageSettings(version: top.foxball.video.entity.jdbc.AiTemplateVersion): ImageSettings {
        val image = policyOf(version).path("image")
        return ImageSettings(
            size = image.path("size").stringValue(null)?.take(20),
            count = image.path("count").takeIf { it.isIntegralNumber }?.intValue()?.coerceIn(1, 10),
            quality = image.path("quality").stringValue(null)?.take(30),
            style = image.path("style").stringValue(null)?.take(60),
        )
    }

    /** 通过受控的图像接口生成图片；返回上游给出的图片地址或 base64 数据。 */
    fun generateImage(
        providerConfigId: Long,
        model: String,
        prompt: String,
        settings: ImageSettings,
        requestId: String,
    ): AiImageGenerationResult {
        require(prompt.isNotBlank()) { "图像提示词不能为空" }
        val command = AiImageGenerationCommand(
            providerConfigId = providerConfigId,
            model = model,
            prompt = prompt,
            count = settings.count,
            size = settings.size,
            quality = settings.quality,
            style = settings.style,
            responseFormat = AiImageResponseFormat.B64_JSON,
            requestId = requestId,
        )
        return aiInvocationService.generateImage(command).block() ?: throw AiProtocolException("图片服务未返回结果")
    }

    /** 把模板产出折叠为项目对话结果，供只关心文本与用量的调用方使用。 */
    fun toCompletionResult(outcome: ChatOutcome): AiChatCompletionResult = AiChatCompletionResult(
        id = outcome.id,
        model = outcome.model,
        choices = listOf(
            top.foxball.video.ai.AiChatChoice(
                index = 0,
                message = AiChatMessage(AiChatRole.ASSISTANT, outcome.text),
                finishReason = "stop",
            ),
        ),
        usage = top.foxball.video.ai.AiTokenUsage(
            inputTokens = outcome.inputTokens,
            outputTokens = outcome.outputTokens,
            totalTokens = null,
        ),
    )

    /**
     * 驱动工具调用循环：模型请求工具时执行工具并把结果回填，直到模型给出最终回答或达到轮数上限。
     * 达到上限仍存在工具请求时返回最后一次响应，由调用方按空内容或异常处理。
     */
    private fun driveToolLoop(model: ChatModel, messages: List<AiChatMessage>, options: OpenAiChatOptions): ToolLoopResult {
        var prompt = Prompt(SpringAiMessageMapper.toSpringMessages(messages), options)
        var response = model.call(prompt)
        var toolCallCount = 0
        var rounds = 0
        while (response.hasToolCalls() && rounds < MAX_TOOL_ROUNDS) {
            val execution = toolCallingManager.executeToolCalls(prompt, response)
            val lastMessage = execution.conversationHistory().lastOrNull()
            if (lastMessage is ToolResponseMessage) toolCallCount += lastMessage.responses.size
            prompt = Prompt(execution.conversationHistory(), options)
            response = model.call(prompt)
            rounds++
        }
        return ToolLoopResult(response, toolCallCount, rounds)
    }

    /** 按生成策略构造对话选项；工具回调同时用于模型请求和工具执行时的解析。 */
    private fun chatOptions(
        version: top.foxball.video.entity.jdbc.AiTemplateVersion,
        model: String,
        toolCallbacks: List<org.springframework.ai.tool.ToolCallback>,
    ): OpenAiChatOptions {
        val policy = policyOf(version)
        val builder = OpenAiChatOptions.builder()
            .model(model)
            .temperature(policy.path("temperature").takeIf { it.isNumber }?.doubleValue())
            .maxCompletionTokens(policy.path("max_completion_tokens").takeIf { it.isIntegralNumber }?.intValue())
        when (policy.path("response_format").stringValue(null)) {
            "json_object" -> builder.responseFormat(
                OpenAiChatModel.ResponseFormat.builder().type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT).build(),
            )
            "json_schema" -> builder.responseFormat(
                OpenAiChatModel.ResponseFormat.builder()
                    .type(OpenAiChatModel.ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(version.outputSchemaJson)
                    .strict(true)
                    .build(),
            )
            else -> Unit
        }
        if (toolCallbacks.isNotEmpty()) builder.toolCallbacks(toolCallbacks)
        return builder.build()
    }

    private fun policyOf(version: top.foxball.video.entity.jdbc.AiTemplateVersion): JsonNode =
        schemaValidator.parseGenerationPolicy(version.generationPolicyJson)

    private data class ToolLoopResult(val response: ChatResponse, val toolCallCount: Int, val rounds: Int)

    private companion object {
        /** 工具调用轮数上限：避免模型在工具结果不理想时无限循环。 */
        const val MAX_TOOL_ROUNDS = 6

        /** Spring AI 的工具执行管理器；无观测注册表时使用默认实现。 */
        val toolCallingManager: ToolCallingManager = DefaultToolCallingManager.builder().build()
    }
}
