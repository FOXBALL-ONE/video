package top.foxball.video.ai.springai

import com.openai.core.http.ProxyAuthenticator
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.image.ImageModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.OpenAiImageModel
import org.springframework.ai.openai.OpenAiImageOptions
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer
import org.springframework.stereotype.Component
import top.foxball.video.ai.AiConnectionProfile
import top.foxball.video.ai.AiProviderConnection
import top.foxball.video.ai.AiProviderUriPolicy
import top.foxball.video.ai.AiProxyMode
import top.foxball.video.ai.AiTransportCache
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * 按已解析的提供商连接构建 Spring AI 的对话与图像模型。
 *
 * 数据库中的每条提供商配置都是一个独立连接，因此这里不使用 Spring AI 的属性化自动装配，
 * 而是把项目已有的代理、超时、自定义请求头等连接档案映射到 Spring AI 的 OpenAI 选项上，
 * 并按连接档案键缓存构建结果。
 */
@Component
class SpringAiModelFactory : AiTransportCache {
    private val uriPolicy = AiProviderUriPolicy()
    private val chatModels = ConcurrentHashMap<String, ChatModel>()
    private val imageModels = ConcurrentHashMap<String, ImageModel>()

    /**
     * 返回指定连接与模型对应的 Spring AI 对话模型。
     * 临时连接（无缓存键）与每次调用使用不同模型的连接不会进入缓存。
     */
    fun chatModel(connection: AiProviderConnection, model: String): ChatModel {
        val cacheKey = connection.profile.cacheKey?.let { "$it|chat|$model" } ?: return buildChatModel(connection, model)
        return chatModels.computeIfAbsent(cacheKey) { buildChatModel(connection, model) }
    }

    /** 返回指定连接对应的 Spring AI 图像生成模型；图像接口每次调用都显式传入模型名。 */
    fun imageModel(connection: AiProviderConnection, model: String): ImageModel {
        val cacheKey = connection.profile.cacheKey?.let { "$it|image|$model" } ?: return buildImageModel(connection, model)
        return imageModels.computeIfAbsent(cacheKey) { buildImageModel(connection, model) }
    }

    /** 丢弃该连接档案键下的全部模型缓存；提供商配置变更后由解析器调用。 */
    override fun invalidate(cacheKey: String) {
        chatModels.keys.removeIf { it.startsWith("$cacheKey|") }
        imageModels.keys.removeIf { it.startsWith("$cacheKey|") }
    }

    private fun buildChatModel(connection: AiProviderConnection, model: String): ChatModel {
        val options = OpenAiChatOptions.builder()
            .baseUrl(openAiBaseUrl(connection))
            .apiKey(connection.apiKey)
            .model(model)
            .timeout(connection.profile.responseTimeout)
            // 重试由 AiInvocationGuard 与业务重试策略统一负责，避免同一请求被叠加放大。
            .maxRetries(0)
            .proxy(outboundProxy(connection))
            .customHeaders(REQUEST_HEADERS)
            .build()
        return OpenAiChatModel.builder()
            .options(options)
            .httpClientBuilderCustomizer(proxyCredentialCustomizer(connection.profile))
            .build()
    }

    private fun buildImageModel(connection: AiProviderConnection, model: String): ImageModel {
        val options = OpenAiImageOptions.builder()
            .baseUrl(openAiBaseUrl(connection))
            .apiKey(connection.apiKey)
            .model(model)
            .timeout(connection.profile.responseTimeout)
            .maxRetries(0)
            .proxy(outboundProxy(connection))
            .customHeaders(REQUEST_HEADERS)
            .build()
        return OpenAiImageModel.builder()
            .options(options)
            .httpClientBuilderCustomizer(proxyCredentialCustomizer(connection.profile))
            .build()
    }

    /**
     * 将项目规范化的服务根地址转换为 OpenAI SDK 需要的地址。
     * 项目根地址已去除末尾 `/v1`，而 SDK 直接在其后拼接 `/chat/completions` 等路径。
     */
    private fun openAiBaseUrl(connection: AiProviderConnection): String =
        connection.baseUri.toString().trimEnd('/') + "/v1"

    /** 无代理的连接返回 null；命中 nonProxyHosts 的白名单主机同样直连。 */
    private fun outboundProxy(connection: AiProviderConnection): Proxy? {
        val profile = connection.profile
        if (profile.proxyMode == AiProxyMode.DIRECT) return null
        val host = requireNotNull(profile.proxyHost)
        val port = requireNotNull(profile.proxyPort)
        val targetHost = connection.baseUri.host ?: return null
        if (profile.nonProxyHosts.any { uriPolicy.matchesHost(it, targetHost) }) return null
        return if (profile.proxyMode == AiProxyMode.SOCKS5) {
            // SOCKS5 使用未解析地址，由代理端完成 DNS，与 AiDnsMode.PROXY 的语义一致。
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port))
        } else {
            Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
        }
    }

    /** OkHttp 不会自动附带代理认证信息，需要显式注册 SDK 提供的代理认证器。 */
    private fun proxyCredentialCustomizer(profile: AiConnectionProfile): OpenAiHttpClientBuilderCustomizer {
        val username = profile.proxyUsername
        val password = profile.proxyPassword
        if (profile.proxyMode == AiProxyMode.DIRECT || username.isNullOrBlank() || password.isNullOrBlank()) {
            return OpenAiHttpClientBuilderCustomizer { }
        }
        return OpenAiHttpClientBuilderCustomizer { builder ->
            builder.proxyAuthenticator(ProxyAuthenticator.basic(username, password))
        }
    }

    private companion object {
        /** 与自研客户端保持一致的标识，便于上游按调用方排查问题。 */
        val REQUEST_HEADERS = mapOf("User-Agent" to "video-ai-client")
    }
}
