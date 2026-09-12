package top.foxball.video.service

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.ai.springai.McpToolRegistrar
import top.foxball.video.entity.jdbc.AiTemplate
import top.foxball.video.entity.jdbc.AiTemplateVersion
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateAgentRunnerTest {
    /** 目标生成策略：图像产出，提示词取自模型输出。 */
    private val imagePolicy =
        """{"response_format":"text","output_mode":"image","image":{"size":"2048x2048","count":1,"prompt_source":"model_output"}}"""

    private val directorSkill = SkillRegistry.SkillSpec(
        key = "director",
        displayName = "导演",
        description = "整体叙事与节奏把控",
        instructions = "你是导演，负责把控整体叙事节奏。",
        tools = listOf("storyboard_list_validate"),
        mcpServers = emptyList(),
        contentHash = "sha256:director",
    )

    private fun version(policy: String = imagePolicy) = AiTemplateVersion(
        id = 11,
        templateId = 10,
        versionNo = 1,
        generationPolicyJson = policy,
        skillsJson = """[{"key":"director"}]""",
        toolsJson = """["storyboard_list_validate"]""",
        mcpServersJson = "[]",
        outputSchemaJson = "{}",
    )

    private fun resolved() = TemplateCatalogService.ResolvedTemplate(
        template = AiTemplate(id = 10, key = "storyboard_generation", displayName = "分镜生成"),
        version = version(),
    )

    private fun rendered() = TemplateRenderer.RenderedTemplate(
        messages = listOf(AiChatMessage(AiChatRole.USER, "请产出九宫格分镜列表")),
        templateHash = "sha256:template",
        variablesHash = "sha256:variables",
        renderedInputHash = "sha256:rendered",
        inputTokens = 0,
        variableNames = emptyList(),
        variableLengths = emptyMap(),
    )

    /**
     * Mockito 的参数匹配器运行期返回 null，而 Kotlin 的非空函数类型参数会在调用点插入空检查。
     * 通过返回类型声明为非空的辅助函数注册匹配器，可绕过该检查并保留匹配语义。
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = Mockito.any<T>()

    @Test
    fun `chat injects skill system block and feeds tool result back to the model`() {
        val finalText = """{"shots":[{"index":1,"description":"远景"}]}"""
        val fakeModel = FakeChatModel(
            listOf(
                toolCallResponse(
                    toolCallId = "call-1",
                    toolName = "storyboard_list_validate",
                    arguments = """{"shots":[{"description":"远景"}]}""",
                ),
                textResponse(finalText),
            ),
        )
        val fixture = Fixture(fakeModel, directorSkill)

        val outcome = fixture.runner.chat(
            TemplateAgentRunner.ChatRequest(
                template = resolved(),
                rendered = rendered(),
                providerConfigId = 1L,
                model = "fake-model",
                requestId = "req-1",
            ),
        )

        assertEquals(finalText, outcome.text)
        assertEquals(1, outcome.toolCallCount)
        assertEquals(listOf("director"), outcome.skillKeys)
        assertEquals(emptyList(), outcome.mcpServerKeys)
        assertEquals(1, outcome.attemptRounds)
        assertEquals("resp-2", outcome.id)
        assertEquals("fake-model", outcome.model)
        assertEquals(10, outcome.inputTokens)
        assertEquals(20, outcome.outputTokens)

        // 第一次调用模型时，技能说明已经作为独立 system 区块注入。
        assertEquals(2, fakeModel.prompts.size)
        val firstInstructions = fakeModel.prompts[0].instructions
        assertTrue(
            firstInstructions.filterIsInstance<SystemMessage>().any { it.text.orEmpty().contains("你是导演") },
            "首个提示词缺少导演技能 system 区块：$firstInstructions",
        )

        // 工具执行结果被回填到第二轮对话历史。
        val secondInstructions = fakeModel.prompts[1].instructions
        val toolResponses = secondInstructions.filterIsInstance<ToolResponseMessage>()
        assertEquals(1, toolResponses.size, "第二轮对话缺少工具结果消息：$secondInstructions")
        assertEquals("call-1", toolResponses.single().responses.single().id())
    }

    @Test
    fun `generation policy drives output mode prompt source and image settings`() {
        val fixture = Fixture(FakeChatModel(emptyList()), null)
        val version = version()

        assertEquals(TemplateOutputMode.IMAGE, fixture.runner.outputMode(version))
        assertEquals(TemplateImagePromptSource.MODEL_OUTPUT, fixture.runner.imagePromptSource(version))

        val settings = fixture.runner.imageSettings(version)
        assertEquals("2048x2048", settings.size)
        assertEquals(1, settings.count)
        assertEquals(null, settings.quality)
        assertEquals(null, settings.style)
    }

    /** 记录收到的提示词，并按顺序返回预设响应的假对话模型。 */
    private class FakeChatModel(private val responses: List<ChatResponse>) : ChatModel {
        val prompts = mutableListOf<Prompt>()
        private var cursor = 0

        override fun call(prompt: Prompt): ChatResponse {
            prompts.add(prompt)
            return responses[cursor++]
        }

        override fun stream(prompt: Prompt): Flux<ChatResponse> =
            throw UnsupportedOperationException("本测试不需要流式响应")
    }

    private inner class Fixture(fakeModel: ChatModel, skillSpec: SkillRegistry.SkillSpec?) {
        private val aiInvocationService: AiInvocationService = Mockito.mock(AiInvocationService::class.java)
        private val skillRegistry: SkillRegistry = Mockito.mock(SkillRegistry::class.java)
        private val mcpToolRegistrar: McpToolRegistrar = Mockito.mock(McpToolRegistrar::class.java)
        private val toolRegistry = TemplateToolRegistry(listOf(StoryboardListTool(ObjectMapper())))

        val runner = TemplateAgentRunner(
            skillRegistry,
            toolRegistry,
            mcpToolRegistrar,
            TemplateSchemaValidator(ObjectMapper()),
            aiInvocationService,
        )

        init {
            Mockito.`when`(
                aiInvocationService.useChatModel<Any>(Mockito.anyLong(), Mockito.anyString(), anyArg<(ChatModel) -> Any>()),
            ).thenAnswer { invocation ->
                val handler = invocation.getArgument<(ChatModel) -> Any>(2)
                Mono.fromCallable { handler(fakeModel) }
            }
            Mockito.`when`(mcpToolRegistrar.toolCallbacks(Mockito.anyString())).thenReturn(emptyList())
            if (skillSpec != null) {
                Mockito.`when`(skillRegistry.resolveSpecs(listOf(skillSpec.key))).thenReturn(listOf(skillSpec))
                Mockito.`when`(skillRegistry.systemBlock(Mockito.anyList())).thenReturn(
                    "<skill key=\"${skillSpec.key}\" name=\"${skillSpec.displayName}\">\n${skillSpec.instructions}\n</skill>",
                )
            }
        }
    }

    private companion object {
        fun toolCallResponse(toolCallId: String, toolName: String, arguments: String): ChatResponse {
            val message = AssistantMessage.builder()
                .content("")
                .toolCalls(listOf(AssistantMessage.ToolCall(toolCallId, "function", toolName, arguments)))
                .build()
            return ChatResponse.builder()
                .generations(listOf(Generation(message)))
                .metadata(metadata("resp-1"))
                .build()
        }

        fun textResponse(text: String): ChatResponse {
            val message = AssistantMessage(text)
            return ChatResponse.builder()
                .generations(listOf(Generation(message)))
                .metadata(metadata("resp-2"))
                .build()
        }

        fun metadata(id: String): ChatResponseMetadata = ChatResponseMetadata.builder()
            .id(id)
            .model("fake-model")
            .usage(DefaultUsage(10, 20))
            .build()
    }
}
