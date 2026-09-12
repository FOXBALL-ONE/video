package top.foxball.video.ai.springai

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper
import io.modelcontextprotocol.spec.McpClientTransport
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import top.foxball.video.entity.jdbc.AiMcpServer
import top.foxball.video.entity.jdbc.McpTransport
import top.foxball.video.service.McpServerRegistry
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 按数据库中的 MCP 服务端配置建立连接，并把服务端工具转换为 Spring AI 的 [ToolCallback]。
 *
 * 工具名统一加服务端前缀（如 `filesystem__read_file`），避免多服务端之间的重名冲突；
 * 连接按服务端 key 缓存，配置变更或删除后由调用方 [refresh] 失效。
 */
@Component
class McpToolRegistrar(
    private val mcpServerRegistry: McpServerRegistry,
    private val objectMapper: ObjectMapper,
) : AutoCloseable {
    /** STDIO 传输需要独立的 JSON 映射器；HTTP 传输使用 SDK 默认实现。 */
    private val standardIoJsonMapper = JacksonMcpJsonMapper(JsonMapper.builder().build())

    private val clients = ConcurrentHashMap<String, McpSyncClient>()
    private val providers = ConcurrentHashMap<String, SyncMcpToolCallbackProvider>()

    /** 返回指定服务端暴露的工具回调；服务端未配置或连接失败时抛出异常。 */
    fun toolCallbacks(serverKey: String): List<ToolCallback> = provider(serverKey).getToolCallbacks().toList()

    /**
     * 返回指定服务端暴露的工具回调，并按调用方声明的工具白名单过滤。
     * 白名单为空表示该服务端全部工具；名称按服务端原始工具名匹配。
     */
    fun toolCallbacks(serverKey: String, toolWhitelist: List<String>): List<ToolCallback> {
        val callbacks = toolCallbacks(serverKey)
        if (toolWhitelist.isEmpty()) return callbacks
        val prefix = prefixOf(serverKey)
        return callbacks.filter { callback -> callback.toolDefinition.name().removePrefix(prefix) in toolWhitelist }
    }

    /** 读取指定服务端当前可用的工具名，用于管理端的连通性测试。 */
    fun listTools(serverKey: String): List<String> = provider(serverKey).getToolCallbacks().map { it.toolDefinition.name() }

    /** 断开并丢弃指定服务端的连接缓存。 */
    fun refresh(serverKey: String) {
        providers.remove(serverKey)
        clients.remove(serverKey)?.let { client -> runCatching { client.closeGracefully() } }
    }

    /** 断开全部 MCP 连接，用于应用关闭。 */
    override fun close() {
        providers.clear()
        clients.values.forEach { client -> runCatching { client.closeGracefully() } }
        clients.clear()
    }

    private fun provider(serverKey: String): SyncMcpToolCallbackProvider =
        providers.computeIfAbsent(serverKey) { key ->
            val server = mcpServerRegistry.getServer(key)
                ?: throw IllegalArgumentException("MCP 服务端未注册：$key")
            require(server.enabled) { "MCP 服务端已停用：$key" }
            val allowed = allowedToolNames(server)
            val client = clients.computeIfAbsent(key) { connect(server, key) }
            SyncMcpToolCallbackProvider.builder()
                .mcpClients(client)
                .toolNamePrefixGenerator { _, tool -> "${prefixOf(key)}${tool.name()}" }
                // 服务端级白名单由管理员维护：未列入的工具不会暴露给模型。
                .toolFilter { _, tool -> allowed.isEmpty() || tool.name() in allowed }
                .build()
        }

    private fun connect(server: AiMcpServer, serverKey: String): McpSyncClient =
        McpClient.sync(transport(server, serverKey))
            .requestTimeout(Duration.ofSeconds(server.requestTimeoutSeconds))
            .build()

    private fun transport(server: AiMcpServer, serverKey: String): McpClientTransport = when (server.transport) {
        McpTransport.STDIO -> stdioTransport(server, serverKey)
        McpTransport.SSE -> sseTransport(server, serverKey)
        McpTransport.STREAMABLE_HTTP -> streamableHttpTransport(server, serverKey)
    }

    /** STDIO 传输忽略服务端请求头，认证靠环境变量传递。 */
    private fun stdioTransport(server: AiMcpServer, serverKey: String): McpClientTransport {
        val command = requireNotNull(server.command?.takeIf { it.isNotBlank() }) { "MCP 服务端缺少启动命令：$serverKey" }
        val parameters = ServerParameters.builder(command)
            .args(stringList(server.argsJson))
            .apply { stringMap(server.envJson).forEach { (name, value) -> addEnvVar(name, value) } }
            .build()
        return StdioClientTransport(parameters, standardIoJsonMapper)
    }

    private fun sseTransport(server: AiMcpServer, serverKey: String): McpClientTransport {
        val baseUri = requireNotNull(server.url?.takeIf { it.isNotBlank() }) { "MCP 服务端缺少地址：$serverKey" }
        val builder = HttpClientSseClientTransport.builder(baseUri.trim())
        builder.requestBuilder(requestBuilder(server.headersJson))
        return builder.build()
    }

    private fun streamableHttpTransport(server: AiMcpServer, serverKey: String): McpClientTransport {
        val baseUri = requireNotNull(server.url?.takeIf { it.isNotBlank() }) { "MCP 服务端缺少地址：$serverKey" }
        val builder = HttpClientStreamableHttpTransport.builder(baseUri.trim())
        builder.requestBuilder(requestBuilder(server.headersJson))
        return builder.build()
    }

    /** 服务端请求头以 JSON 对象保存；非法配置按无请求头处理，不影响连接建立。 */
    private fun requestBuilder(headersJson: String): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder()
        stringMap(headersJson).forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun stringList(json: String): List<String> =
        runCatching { objectMapper.readTree(json) }.getOrNull()
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.stringValue(null) }
            .orEmpty()

    private fun stringMap(json: String): Map<String, String> =
        runCatching { objectMapper.readTree(json) }.getOrNull()
            ?.takeIf { it.isObject }
            ?.properties()
            ?.mapNotNull { (name, value) -> value.stringValue(null)?.let { name to it } }
            ?.toMap()
            .orEmpty()

    /** 服务端级工具白名单：管理员未列入的工具不会暴露给模型；为空表示不限制。 */
    private fun allowedToolNames(server: AiMcpServer): Set<String> =
        runCatching { objectMapper.readTree(server.toolAllowlistJson) }.getOrNull()
            ?.takeIf { it.isArray }
            ?.mapNotNull { it.stringValue(null)?.takeIf(String::isNotBlank) }
            ?.toSet()
            .orEmpty()

    /** 工具名前缀风格：`{server_key}__{tool_name}`，避免不同服务端之间的重名冲突。 */
    private fun prefixOf(serverKey: String): String = "${serverKey.replace('-', '_').lowercase(Locale.ROOT)}__"
}
