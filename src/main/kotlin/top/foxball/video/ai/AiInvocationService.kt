package top.foxball.video.ai

import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.UUID

/** 面向业务服务的 AI 调用门面，负责将配置 ID 解析为受控连接。 */
@Service
class AiInvocationService(
    private val providerResolver: AiProviderResolver,
    private val client: AiModelClient,
) {
    fun listModels(providerConfigId: Long): Mono<List<AiModelDescriptor>> {
        val connection = providerResolver.resolveSaved(providerConfigId)
        return client.listModels(connection)
    }

    /** 仅在新增提供商配置前校验连接时使用，密钥不会离开服务端。 */
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
     * 兼容现有 Spring MVC 模型列表接口的过渡适配器。
     * 上游 I/O 使用响应式客户端；该方法仍等待结果，后续应由响应式端点或异步任务替换。
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

    fun createChatCompletion(command: AiChatCompletionCommand): Mono<AiChatCompletionResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId)
        return client.createChatCompletion(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    fun streamChatCompletion(command: AiChatCompletionCommand): Flux<AiChatCompletionDelta> {
        val connection = providerResolver.resolveSaved(command.providerConfigId)
        return client.streamChatCompletion(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    fun generateImage(command: AiImageGenerationCommand): Mono<AiImageGenerationResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId)
        return client.generateImage(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }

    fun editImage(command: AiImageEditCommand): Mono<AiImageGenerationResult> {
        val connection = providerResolver.resolveSaved(command.providerConfigId)
        return client.editImage(connection, command.copy(requestId = command.requestId ?: UUID.randomUUID().toString()))
    }
}
