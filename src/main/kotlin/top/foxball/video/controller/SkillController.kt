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
import top.foxball.video.service.SkillRegistry
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
/** 技能管理接口。 */
class SkillController(
    private val skillRegistry: SkillRegistry,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/api/ai/skills")
    fun list(@AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class SkillData(
            val id: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val description: String,
            val source: String,
            val enabled: Boolean,
            val tools: Any,
            @param:JsonProperty("mcp_servers") val mcpServers: Any,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val list: List<SkillData>)
        val skills = skillRegistry.list()
        val data = skills.map { skill ->
            SkillData(
                requireNotNull(skill.id), skill.key, skill.displayName, skill.description, skill.source.name, skill.enabled,
                parseJson(skill.toolsJson), parseJson(skill.mcpServersJson), skill.contentHash, skill.updatedAt,
            )
        }
        return responseBuilder.ok().data(Response(data)).build()
    }

    @GetMapping("/api/ai/skills/options")
    fun options(@AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class OptionData(
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val description: String,
            val tools: Any,
            @param:JsonProperty("mcp_servers") val mcpServers: Any,
        )
        data class Response(val list: List<OptionData>)
        val skills = skillRegistry.list().filter { it.enabled }
        val data = skills.map { skill ->
            OptionData(skill.key, skill.displayName, skill.description, parseJson(skill.toolsJson), parseJson(skill.mcpServersJson))
        }
        return responseBuilder.ok().data(Response(data)).build()
    }

    // 技能正文属于内部方法论，只有系统管理员可以读取。
    @GetMapping("/api/ai/skills/{skill_id}")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','system:admin')")
    fun detail(@AuthenticationPrincipal userId: Long?, @PathVariable("skill_id") skillId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val description: String,
            val instructions: String,
            val tools: Any,
            @param:JsonProperty("mcp_servers") val mcpServers: Any,
            val source: String,
            val enabled: Boolean,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("storage_path") val storagePath: String?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        val skill = skillRegistry.findById(skillId) ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(skill.id), skill.key, skill.displayName, skill.description, skill.instructions,
            parseJson(skill.toolsJson), parseJson(skill.mcpServersJson), skill.source.name, skill.enabled,
            skill.contentHash, skill.storagePath, skill.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/ai/skills")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','system:admin')")
    fun create(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("key") @NotBlank @Size(max = 100) key: String,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("description", defaultValue = "") @Size(max = 1000) description: String,
        @RequestParam("instructions") @NotBlank @Size(max = 40000) instructions: String,
        @RequestParam("tools_json", defaultValue = "[]") @Size(max = 20000) toolsJson: String,
        @RequestParam("mcp_servers_json", defaultValue = "[]") @Size(max = 20000) mcpServersJson: String,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val key: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val skill = skillRegistry.save(userId, null, key, displayName, description, instructions, toolsJson, mcpServersJson, enabled)
        return responseBuilder.created().data(Response(requireNotNull(skill.id), skill.key, skill.contentHash)).build()
    }

    @PutMapping("/api/ai/skills/{skill_id}")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','system:admin')")
    fun update(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("skill_id") skillId: Long,
        @RequestParam("key") @NotBlank @Size(max = 100) key: String,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("description", defaultValue = "") @Size(max = 1000) description: String,
        @RequestParam("instructions") @NotBlank @Size(max = 40000) instructions: String,
        @RequestParam("tools_json", defaultValue = "[]") @Size(max = 20000) toolsJson: String,
        @RequestParam("mcp_servers_json", defaultValue = "[]") @Size(max = 20000) mcpServersJson: String,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val key: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val skill = skillRegistry.save(userId, skillId, key, displayName, description, instructions, toolsJson, mcpServersJson, enabled)
        return responseBuilder.ok().data(Response(requireNotNull(skill.id), skill.key, skill.contentHash)).build()
    }

    @DeleteMapping("/api/ai/skills/{skill_id}")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','system:admin')")
    fun delete(@AuthenticationPrincipal userId: Long?, @PathVariable("skill_id") skillId: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)
        val deleted = skillRegistry.delete(skillId)
        if (!deleted) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(skillId, true)).build()
    }

    // 文件导入会扫描服务端存储目录，只允许系统管理员触发。
    @PostMapping("/api/ai/skills/import")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','system:admin')")
    fun importSkills(@AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class Response(val imported: Int)
        val count = skillRegistry.importFromFileSystem()
        return responseBuilder.ok().data(Response(count)).build()
    }

    private fun parseJson(json: String): Any = runCatching { objectMapper.readTree(json) }.getOrElse { json }
}