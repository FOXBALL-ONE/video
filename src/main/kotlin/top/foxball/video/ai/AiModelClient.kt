package top.foxball.video.ai

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** OpenAI 兼容模型服务的底层调用接口；调用前必须已解析并校验提供商连接。 */
interface AiModelClient {
    /** 获取提供商公开的模型目录。 */
    fun listModels(connection: AiProviderConnection): Mono<List<AiModelDescriptor>>

    /** 发起一次非流式聊天补全调用。 */
    fun createChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Mono<AiChatCompletionResult>

    /** 发起流式聊天补全调用，并逐个返回服务端事件增量。 */
    fun streamChatCompletion(
        connection: AiProviderConnection,
        command: AiChatCompletionCommand,
    ): Flux<AiChatCompletionDelta>

    /** 调用图像生成接口并解析生成结果。 */
    fun generateImage(
        connection: AiProviderConnection,
        command: AiImageGenerationCommand,
    ): Mono<AiImageGenerationResult>

    /** 调用图像编辑接口并上传待编辑图片及可选蒙版。 */
    fun editImage(
        connection: AiProviderConnection,
        command: AiImageEditCommand,
    ): Mono<AiImageGenerationResult>

    /** 创建异步视频生成任务。 */
    fun createVideo(
        connection: AiProviderConnection,
        command: AiVideoGenerationCommand,
    ): Mono<AiVideoGenerationResult>

    /** 查询异步视频任务的状态。 */
    fun getVideo(
        connection: AiProviderConnection,
        videoId: String,
        requestId: String? = null,
    ): Mono<AiVideoGenerationResult>

    /** 下载视频任务生成的二进制内容。 */
    fun downloadVideo(
        connection: AiProviderConnection,
        videoId: String,
        variant: String? = null,
        requestId: String? = null,
    ): Mono<AiVideoContent>
}
