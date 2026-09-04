package top.foxball.video.ai

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** OpenAI 兼容模型服务的底层调用接口；调用前必须已解析并校验提供商连接。 */
interface AiModelClient {
    fun listModels(connection: AiProviderConnection): Mono<List<AiModelDescriptor>>

    fun createChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult>

    fun streamChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta>

    fun generateImage(
        connection: AiProviderConnection,
        command: AiImageGenerationCommand,
    ): Mono<AiImageGenerationResult>

    fun editImage(
        connection: AiProviderConnection,
        command: AiImageEditCommand,
    ): Mono<AiImageGenerationResult>

    fun createVideo(
        connection: AiProviderConnection,
        command: AiVideoGenerationCommand,
    ): Mono<AiVideoGenerationResult>

    fun getVideo(
        connection: AiProviderConnection,
        videoId: String,
        requestId: String? = null,
    ): Mono<AiVideoGenerationResult>

    fun downloadVideo(
        connection: AiProviderConnection,
        videoId: String,
        variant: String? = null,
        requestId: String? = null,
    ): Mono<AiVideoContent>
}
