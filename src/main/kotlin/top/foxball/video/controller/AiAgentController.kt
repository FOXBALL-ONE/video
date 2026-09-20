package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.AgentCategory
import top.foxball.video.entity.jdbc.AgentScopeType
import top.foxball.video.entity.jdbc.ProductionStage
import top.foxball.video.service.AgentCatalogService
import top.foxball.video.service.AgentExecutionService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

/** 智能体目录、版本和执行接口。 */
@RestController
class AiAgentController(
    private val catalog: AgentCatalogService,
    private val executionService: AgentExecutionService,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/api/ai/agents")
    fun list(
        @RequestParam("category", required = false) category: AgentCategory?,
        @RequestParam("scope_type", required = false) scopeType: AgentScopeType?,
        @RequestParam("project_id", required = false) projectId: Long?,
    ): ResponseEntity<Response> {
        data class AgentData(
            val id: Long,
            @param:JsonProperty("agent_key") val agentKey: String,
            @param:JsonProperty("display_name") val displayName: String,
            val purpose: String,
            val category: String,
            @param:JsonProperty("scope_type") val scopeType: String,
            val enabled: Boolean,
            @param:JsonProperty("latest_version_no") val latestVersionNo: Int?,
            @param:JsonProperty("latest_status") val latestStatus: String?,
        )
        data class Response(val items: List<AgentData>)
        val items = catalog.listAgents(category, scopeType, projectId).map { agent ->
            val latest = catalog.listVersions(requireNotNull(agent.id)).firstOrNull()
            AgentData(requireNotNull(agent.id), agent.agentKey, agent.displayName, agent.purpose, agent.category.name, agent.scopeType.name, agent.enabled, latest?.versionNo, latest?.status?.name)
        }
        return responseBuilder.ok().data(Response(items)).build()
    }

    @GetMapping("/api/ai/agents/{agent_key}")
    fun detail(
        @PathVariable("agent_key") agentKey: String,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("project_id", required = false) projectId: Long?,
    ): ResponseEntity<Response> {
        data class VersionData(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("template_key") val templateKey: String,
            @param:JsonProperty("template_version_id") val templateVersionId: Long,
            @param:JsonProperty("change_summary") val changeSummary: String,
            @param:JsonProperty("published_at") val publishedAt: java.time.LocalDateTime?,
        )
        data class Response(
            val id: Long,
            @param:JsonProperty("agent_key") val agentKey: String,
            @param:JsonProperty("display_name") val displayName: String,
            val purpose: String,
            val category: String,
            @param:JsonProperty("scope_type") val scopeType: String,
            @param:JsonProperty("project_id") val projectId: Long?,
            @param:JsonProperty("owner_user_id") val ownerUserId: Long?,
            val enabled: Boolean,
            val versions: List<VersionData>,
        )
        val resolved = runCatching { catalog.resolveAgent(agentKey, userId, projectId) }.getOrElse { return responseBuilder.notFound().message(it.message).build() }
        val rs = Response(
            requireNotNull(resolved.agent.id), resolved.agent.agentKey, resolved.agent.displayName, resolved.agent.purpose,
            resolved.agent.category.name, resolved.agent.scopeType.name, resolved.agent.projectId, resolved.agent.ownerUserId,
            resolved.agent.enabled, catalog.listVersions(agentKey, userId, projectId).map { VersionData(requireNotNull(it.id), it.versionNo, it.status.name, it.templateKey, it.templateVersionId, it.changeSummary, it.publishedAt) },
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/agents/{agent_key}/versions/{version_no}")
    fun version(
        @PathVariable("agent_key") agentKey: String,
        @PathVariable("version_no") versionNo: Int,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("project_id", required = false) projectId: Long?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("input_schema") val inputSchema: Any,
            @param:JsonProperty("output_schema") val outputSchema: Any,
            @param:JsonProperty("template_key") val templateKey: String,
            @param:JsonProperty("template_version_id") val templateVersionId: Long,
            @param:JsonProperty("additional_skills") val additionalSkills: Any,
            @param:JsonProperty("additional_mcp_servers") val additionalMcpServers: Any,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: java.time.LocalDateTime?,
        )
        val resolved = runCatching { catalog.resolveAgent(agentKey, versionNo, userId, projectId) }.getOrElse { return responseBuilder.notFound().message(it.message).build() }
        val v = resolved.version
        val rs = Response(requireNotNull(v.id), v.versionNo, v.status.name, objectMapper.readTree(v.inputSchemaJson), objectMapper.readTree(v.outputSchemaJson), v.templateKey, v.templateVersionId, objectMapper.readTree(v.additionalSkillsJson), objectMapper.readTree(v.additionalMcpServersJson), v.contentHash, v.publishedAt)
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/agents/{agent_key}/preview")
    fun preview(
        @PathVariable("agent_key") agentKey: String,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("project_id", required = false) projectId: Long?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("agent_key") val agentKey: String,
            @param:JsonProperty("display_name") val displayName: String,
            @param:JsonProperty("agent_version_id") val agentVersionId: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            @param:JsonProperty("template_key") val templateKey: String,
            @param:JsonProperty("template_version_id") val templateVersionId: Long,
            val skills: List<String>,
            @param:JsonProperty("mcp_servers") val mcpServers: List<String>,
            @param:JsonProperty("builtin_tools") val builtinTools: List<String>,
        )
        return try {
            val plan = executionService.preview(agentKey, userId, projectId)
            responseBuilder.ok().data(Response(plan.agentKey, plan.displayName, plan.agentVersionId, plan.versionNo, plan.templateKey, plan.templateVersionId, plan.skills, plan.mcpServers, plan.builtinTools)).build()
        } catch (exception: Exception) {
            responseBuilder.notFound().message(exception.message).build()
        }
    }

    @PostMapping("/api/ai/agents")
    fun create(
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<Response> {
        data class Response(val id: Long?, @param:JsonProperty("agent_key") val agentKey: String)
        val agent = try {
            catalog.saveAgent(null, body.path("agent_key").stringValue(null).orEmpty(), body.path("display_name").stringValue(null).orEmpty(), body.path("purpose").stringValue(null).orEmpty(), AgentCategory.valueOf(body.path("category").stringValue("GENERAL_ASSISTANT")), AgentScopeType.valueOf(body.path("scope_type").stringValue("GLOBAL")), body.path("project_id").takeIf { it.isIntegralNumber }?.longValue(), body.path("owner_user_id").takeIf { it.isIntegralNumber }?.longValue(), body.path("enabled").takeIf { it.isBoolean }?.booleanValue() ?: true, userId)
        } catch (exception: Exception) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        return responseBuilder.created().data(Response(agent.id, agent.agentKey)).build()
    }

    @PostMapping("/api/ai/agents/{agent_key}/versions")
    fun createVersion(
        @PathVariable("agent_key") agentKey: String,
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<Response> {
        data class Response(val id: Long?, @param:JsonProperty("version_no") val versionNo: Int, val status: String, @param:JsonProperty("content_hash") val contentHash: String)
        return try {
            val version = catalog.saveVersion(agentKey, null, body.path("input_schema").toString(), body.path("output_schema").toString(), body.path("template_key").stringValue(null).orEmpty(), body.path("template_version_id").longValue(), body.path("additional_skills").takeIf { !it.isMissingNode }?.toString() ?: "[]", body.path("additional_mcp_servers").takeIf { !it.isMissingNode }?.toString() ?: "[]", body.path("input_mapping").takeIf { !it.isMissingNode }?.toString() ?: "{}", body.path("output_mapping").takeIf { !it.isMissingNode }?.toString() ?: "{}", body.path("execution_config").takeIf { !it.isMissingNode }?.toString() ?: "{}", body.path("production_stage").stringValue(null)?.let { ProductionStage.valueOf(it) }, body.path("stage_order").intValue(), body.path("task_type").stringValue(null), body.path("change_summary").stringValue(null).orEmpty(), userId)
            responseBuilder.created().data(Response(version.id, version.versionNo, version.status.name, version.contentHash)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PutMapping("/api/ai/agents/{agent_key}/versions/{version_no}")
    fun updateVersion(
        @PathVariable("agent_key") agentKey: String,
        @PathVariable("version_no") versionNo: Int,
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<Response> {
        data class Response(val id: Long?, @param:JsonProperty("version_no") val versionNo: Int, val status: String, @param:JsonProperty("content_hash") val contentHash: String)
        return try {
            val version = catalog.saveVersion(agentKey, versionNo, body.path("input_schema").toString(), body.path("output_schema").toString(), body.path("template_key").stringValue(null).orEmpty(), body.path("template_version_id").longValue(), body.path("additional_skills").takeIf { !it.isMissingNode }?.toString() ?: "[]", body.path("additional_mcp_servers").takeIf { !it.isMissingNode }?.toString() ?: "[]", body.path("input_mapping").takeIf { !it.isMissingNode }?.toString() ?: "{}", body.path("output_mapping").takeIf { !it.isMissingNode }?.toString() ?: "{}", body.path("execution_config").takeIf { !it.isMissingNode }?.toString() ?: "{}", body.path("production_stage").stringValue(null)?.let { ProductionStage.valueOf(it) }, body.path("stage_order").intValue(), body.path("task_type").stringValue(null), body.path("change_summary").stringValue(null).orEmpty(), userId)
            responseBuilder.ok().data(Response(version.id, version.versionNo, version.status.name, version.contentHash)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/ai/agents/{agent_key}/execute")
    fun execute(
        @PathVariable("agent_key") agentKey: String,
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("agent_version_id") val agentVersionId: Long,
            @param:JsonProperty("template_version_id") val templateVersionId: Long,
            @param:JsonProperty("content_hash") val contentHash: String,
            val output: JsonNode,
            val audit: top.foxball.video.service.AgentRunAudit,
        )
        val input = body.path("input")
        if (input.isMissingNode || !input.isObject) return responseBuilder.badRequest().message("input 必须是 JSON 对象").build()
        return try {
            val result = executionService.execute(
                AgentExecutionService.AgentExecutionRequest(
                    agentKey = agentKey,
                    input = input,
                    providerConfigId = body.path("provider_config_id").longValue(),
                    model = body.path("model").stringValue(null).orEmpty(),
                    requestId = body.path("request_id").stringValue(null).orEmpty(),
                    userId = userId,
                    projectId = body.path("project_id").takeIf { it.isIntegralNumber }?.longValue(),
                    episodeId = body.path("episode_id").takeIf { it.isIntegralNumber }?.longValue(),
                    taskId = body.path("task_id").takeIf { it.isIntegralNumber }?.longValue(),
                    productionStage = body.path("production_stage").stringValue(null)?.let { ProductionStage.valueOf(it) },
                    versionNo = body.path("version_no").takeIf { it.isIntegralNumber }?.intValue(),
                    autoFillResult = body.path("auto_fill_result").takeIf { it.isBoolean }?.booleanValue() ?: true,
                ),
            ).block()!!
            responseBuilder.ok().data(Response(result.agentVersionId, result.templateVersionId, result.contentHash, result.output, result.audit)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/ai/agents/{agent_key}/versions/{version_no}/submit-review")
    fun submitReview(@PathVariable("agent_key") agentKey: String, @PathVariable("version_no") versionNo: Int, @AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class Response(val id: Long?, @param:JsonProperty("version_no") val versionNo: Int, val status: String, @param:JsonProperty("content_hash") val contentHash: String)
        return try {
            val version = catalog.submitReview(agentKey, versionNo, userId)
            responseBuilder.ok().data(Response(version.id, version.versionNo, version.status.name, version.contentHash)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/ai/agents/{agent_key}/versions/{version_no}/publish")
    fun publish(@PathVariable("agent_key") agentKey: String, @PathVariable("version_no") versionNo: Int, @AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class Response(val id: Long?, @param:JsonProperty("version_no") val versionNo: Int, val status: String, @param:JsonProperty("content_hash") val contentHash: String)
        return try {
            val version = catalog.publishVersion(agentKey, versionNo, userId)
            responseBuilder.ok().data(Response(version.id, version.versionNo, version.status.name, version.contentHash)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    @PostMapping("/api/ai/agents/{agent_key}/versions/{version_no}/archive")
    fun archive(@PathVariable("agent_key") agentKey: String, @PathVariable("version_no") versionNo: Int, @AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class Response(val id: Long?, @param:JsonProperty("version_no") val versionNo: Int, val status: String)
        return try {
            val version = catalog.archiveVersion(agentKey, versionNo, userId)
            responseBuilder.ok().data(Response(version.id, version.versionNo, version.status.name)).build()
        } catch (exception: Exception) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

}
