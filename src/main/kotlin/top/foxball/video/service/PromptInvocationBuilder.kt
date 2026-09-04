package top.foxball.video.service

import org.springframework.stereotype.Service
import top.foxball.video.ai.AiChatCompletionCommand

/** 将已校验、已渲染的提示词转换为受控的对话调用命令。 */
@Service
class PromptInvocationBuilder(private val schemaValidator: PromptSchemaValidator) {
    fun build(
        prompt: PromptCatalogService.ResolvedPrompt,
        rendered: PromptRenderer.RenderedPrompt,
        providerConfigId: Long,
        model: String,
        requestId: String,
    ): AiChatCompletionCommand {
        require(providerConfigId > 0) { "提供商配置不正确" }
        require(model.trim().isNotBlank()) { "模型不能为空" }
        val policy = schemaValidator.parseGenerationPolicy(prompt.version.generationPolicyJson)
        val responseFormat = policy.path("response_format").takeIf { it.isTextual }?.textValue()?.takeIf {
            it == "text" || it == "json_object" || it == "json_schema"
        }
        return AiChatCompletionCommand(
            providerConfigId = providerConfigId,
            model = model.trim(),
            messages = rendered.messages,
            temperature = policy.path("temperature").takeIf { it.isNumber }?.doubleValue(),
            maxCompletionTokens = policy.path("max_completion_tokens").takeIf { it.isIntegralNumber }?.intValue(),
            responseFormat = responseFormat,
            responseSchema = responseFormat?.takeIf { it == "json_schema" }?.let { schemaValidator.parseOutputSchema(prompt.version.outputSchemaJson) },
            requestId = requestId,
        )
    }
}
