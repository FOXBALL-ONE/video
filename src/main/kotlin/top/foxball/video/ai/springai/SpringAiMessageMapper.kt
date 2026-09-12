package top.foxball.video.ai.springai

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatResponse
import top.foxball.video.ai.AiChatChoice
import top.foxball.video.ai.AiChatCompletionResult
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiChatToolCall
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiTokenUsage

/** 项目对话消息与 Spring AI 消息模型之间的双向适配。 */
object SpringAiMessageMapper {
    /** 把项目对话消息转换为 Spring AI 消息；角色与工具调用字段必须齐备。 */
    fun toSpringMessages(messages: List<AiChatMessage>): List<Message> {
        if (messages.isEmpty()) throw AiConfigurationException("对话消息不能为空")
        return messages.map { message ->
            if (message.role != AiChatRole.TOOL && message.content.isNullOrBlank() && message.toolCalls.isEmpty()) {
                throw AiConfigurationException("对话消息必须包含文本或工具调用")
            }
            when (message.role) {
                AiChatRole.SYSTEM -> SystemMessage(message.content.orEmpty())
                AiChatRole.USER -> UserMessage(message.content.orEmpty())
                AiChatRole.ASSISTANT -> {
                    val content = message.content.orEmpty()
                    if (message.toolCalls.isEmpty()) {
                        AssistantMessage(content)
                    } else {
                        AssistantMessage.builder()
                            .content(content)
                            .toolCalls(
                                message.toolCalls.map {
                                    AssistantMessage.ToolCall(it.id, "function", it.name, it.arguments)
                                },
                            )
                            .build()
                    }
                }
                AiChatRole.TOOL -> {
                    val toolCallId = message.toolCallId?.takeIf { it.isNotBlank() }
                        ?: throw AiConfigurationException("工具消息缺少 tool_call_id")
                    ToolResponseMessage.builder()
                        .responses(listOf(ToolResponseMessage.ToolResponse(toolCallId, message.name.orEmpty(), message.content.orEmpty())))
                        .build()
                }
            }
        }
    }

    /** 把 Spring AI 响应折叠为项目对话结果；没有候选时返回空 choices 而不是抛错。 */
    fun toCompletionResult(response: ChatResponse, fallbackModel: String): AiChatCompletionResult {
        val generation = response.result ?: return AiChatCompletionResult(null, fallbackModel, emptyList(), null)
        return AiChatCompletionResult(
            id = response.metadata.id,
            model = response.metadata.model ?: fallbackModel,
            choices = listOf(
                AiChatChoice(
                    index = 0,
                    message = AiChatMessage(
                        role = AiChatRole.ASSISTANT,
                        content = generation.output.text,
                        toolCalls = generation.output.toolCalls.map { AiChatToolCall(it.id(), it.name(), it.arguments()) },
                    ),
                    finishReason = generation.metadata.finishReason,
                ),
            ),
            usage = toUsage(response),
        )
    }

    /** 读取响应的 token 用量；上游未返回时为空。 */
    fun toUsage(response: ChatResponse): AiTokenUsage? {
        val usage = response.metadata.usage ?: return null
        return AiTokenUsage(
            inputTokens = usage.promptTokens,
            outputTokens = usage.completionTokens,
            totalTokens = usage.totalTokens,
        )
    }
}
