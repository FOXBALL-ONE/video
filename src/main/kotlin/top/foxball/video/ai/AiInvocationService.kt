package top.foxball.video.ai

import org.springframework.ai.chat.model.ChatModel
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import top.foxball.video.ai.springai.SpringAiExceptionMapper
import top.foxball.video.ai.springai.SpringAiModelFactory
import java.util.UUID

/** 面向业务服务的 AI 调用门面，负责将配置 ID 解析为受控连接。 */
@Service
class AiInvocationService(
    private val providerResolver: AiProviderResolver,
    private val client: AiModelClient,
    private val resultDownloader: AiResultDownloader,
    private val modelFactory: SpringAiModelFactory,
    private val invocationGuard: AiInvocationGuard,
    private val springAiExceptionMapper: SpringAiExceptionMapper,
) {
    /** 解析已保存配置并异步获取该提供商支持的模型列表。 */
    fun listModels(providerConfigId: Long): Mono<List<AiModelDescriptor>> {
        val connection = providerResolver.resolveSaved(providerConfigId)
        return client.listModels(connection)
    }

    /**
     * 仅在新增提供商配置前校验连接时使用，密钥不会离开服务端。
     * 返回值仍为响应式类型，便于调用方选择异步或兼容旧接口的阻塞方式。
     */
    fun listTransientModels(
        provider: String,
        baseUrl: String,
        apiKey: String,
        connectionSettings: AiProviderConnectionSettings = AiProviderConnectionSettings(),
    ): Mono<List<AiModelDescriptor>> {
        val connection = providerResolver.resolveTransient(provider, baseUrl, apiKey, connectionSettings)
        return client.listModels(connection)
    }

    /**
     * 以阻塞方式获取临时提供商的模型列表，供仍使用同步 Spring MVC 的旧接口过渡调用。
     * 实际网络操作在 boundedElastic 调度器执行，避免占用响应式事件循环线程。
     */
    fun listModelsBlocking(
        provider: String,
        baseUrl: String,
        apiKey: String,
        connectionSettings: AiProviderConnectionSettings = AiProviderConnectionSettings(),
    ): List<AiModelDescriptor> =
        listTransientModels(provider, baseUrl, apiKey, connectionSettings)
            .subscribeOn(Schedulers.boundedElastic())
            .block()
            .orEmpty()

    /** 校验聊天配置类型并创建一次非流式对话补全请求。 */
    fun createChatCompletion(command: AiChatCompletionCommand): Mono<AiChatCompletionResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId, AiApiType.CHAT)
        return client.createChatCompletion(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    /** 校验聊天配置类型并返回按 SSE 增量推送的流式对话结果。 */
    fun streamChatCompletion(command: AiChatCompletionCommand): Flux<AiChatCompletionDelta> {
        val connection = providerResolver.resolveSaved(command.providerConfigId, AiApiType.CHAT)
        return client.streamChatCompletion(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    /** 解析图像能力配置并发起图像生成请求。 */
    fun generateImage(command: AiImageGenerationCommand): Mono<AiImageGenerationResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId, AiApiType.IMAGE)
        return client.generateImage(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    /** 解析图像能力配置并发起图像编辑请求。 */
    fun editImage(command: AiImageEditCommand): Mono<AiImageGenerationResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId, AiApiType.IMAGE)
        return client.editImage(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    /** 解析视频能力配置并创建异步视频生成任务。 */
    fun createVideo(command: AiVideoGenerationCommand): Mono<AiVideoGenerationResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId, AiApiType.VIDEO)
        return client.createVideo(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    /** 查询指定视频任务的当前状态和进度。 */
    fun getVideo(providerConfigId: Long, videoId: String, requestId: String? = null): Mono<AiVideoGenerationResult> {
        val connection = providerResolver.resolveSaved(providerConfigId, AiApiType.VIDEO)
        return client.getVideo(connection, videoId, requestId ?: UUID.randomUUID().toString())
    }

    /** 下载已生成视频的二进制内容或指定的缩略图、雪碧图变体。 */
    fun downloadVideo(
        providerConfigId: Long,
        videoId: String,
        variant: String? = null,
        requestId: String? = null,
    ): Mono<AiVideoContent> {
        val connection = providerResolver.resolveSaved(providerConfigId, AiApiType.VIDEO)
        return client.downloadVideo(connection, videoId, variant, requestId ?: UUID.randomUUID().toString())
    }

    /** 通过安全下载器获取模型返回的临时图片，避免调用方直连外部 URL。 */
    fun downloadImage(providerConfigId: Long, imageUrl: String): Mono<AiResultDownloader.DownloadedAiImage> {
        val connection = providerResolver.resolveSaved(providerConfigId, AiApiType.IMAGE)
        return resultDownloader.download(connection, imageUrl)
    }

    /**
     * 在提供商并发闸门与断路器的保护下取得 Spring AI 对话模型。
     *
     * 模板执行器需要在模型之上自行驱动工具调用循环并按模板策略装配工具与结构化输出选项，
     * 因此这里返回模型本身而不是装配好的客户端。网络调用在 boundedElastic 调度器上以阻塞
     * 方式执行，与项目其余任务线程保持一致。
     */
    fun <T : Any> useChatModel(providerConfigId: Long, model: String, invocation: (ChatModel) -> T): Mono<T> {
        val connection = providerResolver.resolveSaved(providerConfigId, AiApiType.CHAT)
        return invocationGuard.protect(connection, AiOperation.CHAT) {
            Mono.fromCallable { invocation(modelFactory.chatModel(connection, model)) }
                .subscribeOn(Schedulers.boundedElastic())
                // SDK 异常必须翻译为项目异常：断路器只统计 AiClientException，业务层也只按项目异常映射错误码。
                .onErrorMap(springAiExceptionMapper::translate)
        }
    }
}
