package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import top.foxball.video.ai.AiChatCompletionCommand
import top.foxball.video.ai.AiChatMessage
import top.foxball.video.ai.AiChatRole
import top.foxball.video.ai.AiInvocationService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@Validated
@RestController
/** 纯文字 AI 调用接口，供剧本解析和调试型对话使用。 */
class AiChatController(
    private val aiInvocationService: AiInvocationService,
    private val responseBuilder: ResponseBuilder,
) {
    @PostMapping("/api/ai/chat/completions", params = ["stream=false"])
    fun complete(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
        @RequestParam("prompt") @NotBlank @Size(max = 200000) prompt: String,
        @RequestParam("stream", defaultValue = "false") stream: Boolean,
    ): ResponseEntity<Response> {
        data class ResponseData(
            val id: String?,
            val model: String?,
            val content: String,
            @param:JsonProperty("finish_reason") val finishReason: String?,
            @param:JsonProperty("input_tokens") val inputTokens: Int?,
            @param:JsonProperty("output_tokens") val outputTokens: Int?,
            @param:JsonProperty("total_tokens") val totalTokens: Int?,
        )
        val result = aiInvocationService.createChatCompletion(
            AiChatCompletionCommand(
                providerConfigId = providerConfigId,
                model = model.trim(),
                messages = listOf(AiChatMessage(AiChatRole.USER, prompt)),
            ),
        ).block() ?: error("对话服务未返回结果")
        val choice = result.primaryChoice ?: error("对话服务未返回内容")
        val usage = result.usage
        val rs = ResponseData(result.id, result.model, choice.message.content.orEmpty(), choice.finishReason, usage?.inputTokens, usage?.outputTokens, usage?.totalTokens)
        return responseBuilder.ok().data(rs).build()
    }

    /** 默认以 SSE 流式返回聊天增量；前端传入 stream=false 时使用完整 JSON 响应。 */
    @PostMapping(value = ["/api/ai/chat/completions"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @AuthenticationPrincipal userId: Long,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @NotBlank @Size(max = 200) model: String,
        @RequestParam("prompt") @NotBlank @Size(max = 200000) prompt: String,
        @RequestParam("stream", defaultValue = "true") stream: Boolean,
    ): Flux<ServerSentEvent<Any>> {
        require(stream) { "流式接口的 stream 参数必须为 true" }
        data class ResponseData(
            val id: String?,
            val model: String?,
            val content: String?,
            @param:JsonProperty("finish_reason") val finishReason: String?,
        )
        return aiInvocationService.streamChatCompletion(
            AiChatCompletionCommand(
                providerConfigId = providerConfigId,
                model = model.trim(),
                messages = listOf(AiChatMessage(AiChatRole.USER, prompt)),
            ),
        ).map { delta ->
            val event = if (delta.finishReason == null) "delta" else "complete"
            ServerSentEvent.builder<Any>()
                .event(event)
                .data(ResponseData(delta.id, model.trim(), delta.content, delta.finishReason))
                .build()
        }
    }
}
