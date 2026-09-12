package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.AiMcpServer
import top.foxball.video.entity.jdbc.McpTransport
import top.foxball.video.repository.AiMcpServerRepository

/**
 * MCP 服务端注册表。
 *
 * MCP 服务端向模型暴露外部工具（文件系统、数据库、HTTP API 等）。连接信息（命令、环境变量、
 * 请求头）只保存在服务端，管理接口返回时会脱敏，模板与技能通过稳定的 key 引用服务端。
 */
@Service
class McpServerRegistry(
    private val repository: AiMcpServerRepository,
    private val objectMapper: ObjectMapper,
) {
    @Transactional(readOnly = true)
    fun list(): List<AiMcpServer> = repository.findAllByOrderByKeyAsc()

    @Transactional(readOnly = true)
    fun findById(id: Long): AiMcpServer? = repository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun getServer(serverKey: String): AiMcpServer? =
        repository.findAllByKeyOrderByIdAsc(normalizeKey(serverKey)).firstOrNull { it.enabled }

    /** 校验 MCP 服务端引用；未注册或已停用都会导致模板无法发布。 */
    @Transactional(readOnly = true)
    fun requireAvailable(serverKeys: Collection<String>) {
        if (serverKeys.isEmpty()) return
        val available = repository.findAllByOrderByKeyAsc().filter { it.enabled }.map { it.key }.toSet()
        val missing = serverKeys.filter { it !in available }
        require(missing.isEmpty()) { "未注册或已停用的 MCP 服务端：${missing.joinToString(", ")}" }
    }

    /** 新增或更新 MCP 服务端；`id` 为空时按 key 新建。 */
    @Transactional
    fun save(
        userId: Long?,
        id: Long?,
        key: String,
        displayName: String,
        description: String,
        transport: McpTransport,
        command: String?,
        argsJson: String,
        envJson: String,
        url: String?,
        headersJson: String,
        toolAllowlistJson: String,
        requestTimeoutSeconds: Long,
        enabled: Boolean,
    ): AiMcpServer {
        val normalizedKey = normalizeKey(key)
        require(requestTimeoutSeconds in 1..600) { "请求超时必须在 1 到 600 秒之间" }
        when (transport) {
            McpTransport.STDIO -> require(!command.isNullOrBlank()) { "STDIO 传输必须提供启动命令" }
            McpTransport.SSE, McpTransport.STREAMABLE_HTTP -> require(!url.isNullOrBlank()) { "HTTP 传输必须提供服务地址" }
        }
        url?.takeIf { it.isNotBlank() }?.let { require(it.startsWith("http://") || it.startsWith("https://")) { "MCP 服务地址必须是 HTTP(S) 地址" } }
        validateJsonObject(envJson, "环境变量")
        validateJsonObject(headersJson, "请求头")
        validateJsonArray(argsJson, "启动参数")
        validateJsonArray(toolAllowlistJson, "工具白名单")
        val server = id?.let { repository.findById(it).orElse(null) }
            ?: repository.findAllByKeyOrderByIdAsc(normalizedKey).firstOrNull()
            ?: AiMcpServer(key = normalizedKey, createdBy = userId)
        server.key = normalizedKey
        server.displayName = displayName.trim().take(200)
        server.description = description.trim().take(1000)
        server.transport = transport
        server.command = command?.trim()?.take(500)
        server.argsJson = argsJson.trim().ifBlank { "[]" }
        server.envJson = envJson.trim().ifBlank { "{}" }
        server.url = url?.trim()?.take(1000)
        server.headersJson = headersJson.trim().ifBlank { "{}" }
        server.toolAllowlistJson = toolAllowlistJson.trim().ifBlank { "[]" }
        server.requestTimeoutSeconds = requestTimeoutSeconds
        server.enabled = enabled
        return repository.save(server)
    }

    @Transactional
    fun delete(id: Long): Boolean {
        val server = repository.findById(id).orElse(null) ?: return false
        repository.delete(server)
        return true
    }

    /** 管理接口返回前对连接信息脱敏：保留键名，值替换为掩码。 */
    fun maskSecrets(json: String): Map<String, String> {
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return emptyMap()
        if (!root.isObject) return emptyMap()
        return root.properties().associate { (name, value) -> name to if (value.stringValue(null).isNullOrEmpty()) "" else MASK }
    }

    private fun validateJsonObject(json: String, label: String) {
        if (json.isBlank()) return
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: throw IllegalArgumentException("$label 不是合法 JSON")
        require(root.isObject) { "$label 必须是 JSON 对象" }
        require(root.size() <= MAX_ENTRIES) { "$label 条目过多" }
        root.properties().forEach { (name, value) ->
            require(value.isValueNode) { "$label 的值必须是标量：$name" }
        }
    }

    private fun validateJsonArray(json: String, label: String) {
        if (json.isBlank()) return
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: throw IllegalArgumentException("$label 不是合法 JSON")
        require(root.isArray) { "$label 必须是 JSON 数组" }
        require(root.size() <= MAX_ENTRIES) { "$label 条目过多" }
        root.forEach { require(it.isValueNode) { "$label 的元素必须是标量" } }
    }

    private fun normalizeKey(key: String): String = key.trim().lowercase()
        .also { require(it.matches(Regex("[a-z][a-z0-9_]{1,99}"))) { "MCP 服务端 key 格式不正确" } }

    private companion object {
        const val MASK = "******"
        const val MAX_ENTRIES = 64
    }
}
