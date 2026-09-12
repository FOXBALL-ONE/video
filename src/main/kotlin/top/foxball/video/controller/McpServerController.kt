package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.springai.McpToolRegistrar
import top.foxball.video.entity.jdbc.AiMcpServer
import top.foxball.video.entity.jdbc.McpTransport
import top.foxball.video.service.McpServerRegistry
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
// MCP 服务端配置等同于基础设施凭据：连接地址、环境变量与请求头都只允许系统管理员维护。
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','system:admin')")
/** MCP 服务端管理接口。 */
class McpServerController(
    private val mcpServerRegistry: McpServerRegistry,
    private val mcpToolRegistrar: McpToolRegistrar,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/api/ai/mcp-servers")
    fun list(@AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class ServerData(
            val id: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val description: String,
            val transport: String,
            val command: String?,
            val url: String?,
            val args: Any,
            val env: Map<String, String>,
            val headers: Map<String, String>,
            @param:JsonProperty("tool_allowlist") val toolAllowlist: Any,
            @param:JsonProperty("request_timeout_seconds") val requestTimeoutSeconds: Long,
            val enabled: Boolean,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val list: List<ServerData>)
        val servers = mcpServerRegistry.list()
        val data = servers.map { server ->
            ServerData(
                requireNotNull(server.id), server.key, server.displayName, server.description, server.transport.name,
                server.command, server.url, parseJson(server.argsJson),
                mcpServerRegistry.maskSecrets(server.envJson), mcpServerRegistry.maskSecrets(server.headersJson),
                parseJson(server.toolAllowlistJson), server.requestTimeoutSeconds, server.enabled, server.updatedAt,
            )
        }
        return responseBuilder.ok().data(Response(data)).build()
    }

    @GetMapping("/api/ai/mcp-servers/{server_id}")
    fun detail(@AuthenticationPrincipal userId: Long?, @PathVariable("server_id") serverId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val description: String,
            val transport: String,
            val command: String?,
            val url: String?,
            val args: Any,
            val env: Map<String, String>,
            val headers: Map<String, String>,
            @param:JsonProperty("tool_allowlist") val toolAllowlist: Any,
            @param:JsonProperty("request_timeout_seconds") val requestTimeoutSeconds: Long,
            val enabled: Boolean,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        val server = mcpServerRegistry.findById(serverId) ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(server.id), server.key, server.displayName, server.description, server.transport.name,
            server.command, server.url, parseJson(server.argsJson), mcpServerRegistry.maskSecrets(server.envJson),
            mcpServerRegistry.maskSecrets(server.headersJson), parseJson(server.toolAllowlistJson),
            server.requestTimeoutSeconds, server.enabled, server.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/ai/mcp-servers")
    fun create(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("key") @NotBlank @Size(max = 100) key: String,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("description", defaultValue = "") @Size(max = 1000) description: String,
        @RequestParam("transport", defaultValue = "STREAMABLE_HTTP") transport: McpTransport,
        @RequestParam("command", required = false) @Size(max = 500) command: String?,
        @RequestParam("args_json", defaultValue = "[]") argsJson: String,
        @RequestParam("env_json", defaultValue = "{}") envJson: String,
        @RequestParam("url", required = false) @Size(max = 1000) url: String?,
        @RequestParam("headers_json", defaultValue = "{}") headersJson: String,
        @RequestParam("tool_allowlist_json", defaultValue = "[]") toolAllowlistJson: String,
        @RequestParam("request_timeout_seconds", defaultValue = "30") requestTimeoutSeconds: Long,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val key: String)
        val server = mcpServerRegistry.save(
            userId, null, key, displayName, description, transport, command, argsJson, envJson, url,
            headersJson, toolAllowlistJson, requestTimeoutSeconds, enabled,
        )
        mcpToolRegistrar.refresh(server.key)
        return responseBuilder.created().data(Response(requireNotNull(server.id), server.key)).build()
    }

    @PutMapping("/api/ai/mcp-servers/{server_id}")
    fun update(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("server_id") serverId: Long,
        @RequestParam("key") @NotBlank @Size(max = 100) key: String,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("description", defaultValue = "") @Size(max = 1000) description: String,
        @RequestParam("transport", defaultValue = "STREAMABLE_HTTP") transport: McpTransport,
        @RequestParam("command", required = false) @Size(max = 500) command: String?,
        @RequestParam("args_json", defaultValue = "[]") argsJson: String,
        @RequestParam("env_json", defaultValue = "{}") envJson: String,
        @RequestParam("url", required = false) @Size(max = 1000) url: String?,
        @RequestParam("headers_json", defaultValue = "{}") headersJson: String,
        @RequestParam("tool_allowlist_json", defaultValue = "[]") toolAllowlistJson: String,
        @RequestParam("request_timeout_seconds", defaultValue = "30") requestTimeoutSeconds: Long,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val key: String)
        val previousKey = mcpServerRegistry.findById(serverId)?.key
        val server = mcpServerRegistry.save(
            userId, serverId, key, displayName, description, transport, command, argsJson, envJson, url,
            headersJson, toolAllowlistJson, requestTimeoutSeconds, enabled,
        )
        mcpToolRegistrar.refresh(server.key)
        // 改名后旧 key 的客户端与工具缓存必须一并失效，否则旧 key 仍会命中过期连接。
        if (previousKey != null && previousKey != server.key) mcpToolRegistrar.refresh(previousKey)
        return responseBuilder.ok().data(Response(requireNotNull(server.id), server.key)).build()
    }

    @DeleteMapping("/api/ai/mcp-servers/{server_id}")
    fun delete(@AuthenticationPrincipal userId: Long?, @PathVariable("server_id") serverId: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)
        val server = mcpServerRegistry.findById(serverId) ?: return responseBuilder.notFound().build()
        val deleted = mcpServerRegistry.delete(serverId)
        mcpToolRegistrar.refresh(server.key)
        if (!deleted) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(serverId, true)).build()
    }

    @GetMapping("/api/ai/mcp-servers/{server_id}/tools")
    fun tools(@AuthenticationPrincipal userId: Long?, @PathVariable("server_id") serverId: Long): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("server_key") val serverKey: String, val tools: List<String>)
        val server = mcpServerRegistry.findById(serverId) ?: return responseBuilder.notFound().build()
        val result = runCatching { mcpToolRegistrar.listTools(server.key) }
            .getOrElse {
                val message = it.message ?: it::class.java.simpleName
                return responseBuilder.badRequest().message("MCP 服务端连接失败：" + message.take(300)).build()
            }
        return responseBuilder.ok().data(Response(server.key, result)).build()
    }

    private fun parseJson(json: String): Any = runCatching { objectMapper.readTree(json) }.getOrElse { json }
}