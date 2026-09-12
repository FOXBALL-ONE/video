package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
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
import top.foxball.video.entity.jdbc.TemplateScopeType
import top.foxball.video.entity.jdbc.TemplateCategory
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.TemplateVersionStatus
import top.foxball.video.service.TemplateCatalogService
import top.foxball.video.service.TemplateRenderer
import top.foxball.video.service.TemplateToolRegistry
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
/** 模板目录、版本生命周期与运行预览接口。 */
class TemplateController(
    private val catalog: TemplateCatalogService,
    private val renderer: TemplateRenderer,
    private val toolRegistry: TemplateToolRegistry,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/api/ai/template-tools")
    fun tools(@AuthenticationPrincipal userId: Long?): ResponseEntity<Response> {
        data class ToolData(val name: String, val description: String)
        data class Response(val list: List<ToolData>)
        val rs = Response(toolRegistry.describeTools().map { ToolData(it.name, it.description) })
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/templates")
    fun list(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("key", required = false) key: String?,
        @RequestParam("scope_type", required = false) scopeType: TemplateScopeType?,
        @RequestParam("project_id", required = false) projectId: Long?,
        @RequestParam("status", required = false) status: TemplateVersionStatus?,
        @RequestParam("category", required = false) category: TemplateCategory?,
    ): ResponseEntity<Response> {
        data class PromptData(
            val id: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val purpose: String,
            @param:JsonProperty("scope_type") val scopeType: String,
            @param:JsonProperty("project_id") val projectId: Long?,
            @param:JsonProperty("latest_version_no") val latestVersionNo: Int?,
            @param:JsonProperty("latest_version_id") val latestVersionId: Long?,
            @param:JsonProperty("published_version_no") val publishedVersionNo: Int?,
            @param:JsonProperty("published_version_id") val publishedVersionId: Long?,
            @param:JsonProperty("default_locale") val defaultLocale: String,
            val enabled: Boolean,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
            val category: String,
            @param:JsonProperty("category_default") val categoryDefault: Boolean,
        )
        data class Response(val list: List<PromptData>)
        val templates = catalog.list(userId, key, scopeType, projectId, status, category)
        val versionsByTemplateId = catalog.versionsByTemplateIds(templates.mapNotNull { it.id })
        val prompts = templates.map { template ->
            val versions = versionsByTemplateId[template.id].orEmpty()
            PromptData(
                requireNotNull(template.id), template.key, template.displayName, template.purpose, template.scopeType.name, template.projectId,
                versions.maxOfOrNull { it.versionNo }, versions.maxByOrNull { it.versionNo }?.id,
                versions.firstOrNull { it.status == TemplateVersionStatus.PUBLISHED }?.versionNo,
                versions.firstOrNull { it.status == TemplateVersionStatus.PUBLISHED }?.id,
                template.defaultLocale, template.enabled, template.updatedAt, catalog.effectiveCategory(template).name, template.categoryDefault == true,
            )
        }
        return responseBuilder.ok().data(Response(prompts)).build()
    }

    @GetMapping("/api/ai/templates/options")
    fun options(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("project_id") @Min(1) projectId: Long,
        @RequestParam("asset_type") assetType: EpisodeAssetType,
        @RequestParam("locale", defaultValue = "zh-CN") @Size(max = 20) locale: String,
    ): ResponseEntity<Response> {
        data class OptionData(
            @param:JsonProperty("template_id") val templateId: Long,
            @param:JsonProperty("template_key") val templateKey: String,
            @param:JsonProperty("display_name") val displayName: String,
            val purpose: String,
            @param:JsonProperty("scope_type") val scopeType: String,
            @param:JsonProperty("project_id") val projectId: Long?,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            val category: String,
            val locale: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("output_schema_version") val outputSchemaVersion: String,
            @param:JsonProperty("is_default") val isDefault: Boolean,
            val fallback: Boolean,
            @param:JsonProperty("fallback_reason") val fallbackReason: String?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(
            @param:JsonProperty("asset_type") val assetType: String,
            val category: String,
            val locale: String,
            @param:JsonProperty("default_version_id") val defaultVersionId: Long?,
            val list: List<OptionData>,
        )
        val category = try {
            catalog.categoryForAssetType(assetType)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val resolved = try {
            catalog.scriptAssetTemplateOptions(assetType, projectId, userId, locale).toMutableList()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        var fallback = false
        if (resolved.isEmpty()) {
            val legacy = runCatching { catalog.resolve("script_analysis", projectId, userId, locale) }.getOrNull()
            if (legacy != null) {
                resolved += legacy
                fallback = true
            }
        }
        val defaultVersionId = resolved.firstOrNull()?.version?.id
        val rs = Response(
            assetType.name,
            category.name,
            locale.trim().ifBlank { "zh-CN" },
            defaultVersionId,
            resolved.map { item ->
                val version = item.version
                OptionData(
                    requireNotNull(item.template.id), item.template.key, item.template.displayName, item.template.purpose,
                    item.template.scopeType.name, item.template.projectId, requireNotNull(version.id), version.versionNo,
                    version.status.name, category.name, version.locale, version.contentHash, "${item.template.key}.v${version.versionNo}",
                    !fallback && requireNotNull(version.id) == defaultVersionId, fallback, if (fallback) "LEGACY_GENERAL_PROMPT" else null, version.updatedAt,
                )
            },
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/templates/{template_id}/versions/{version_id}")
    fun detail(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
        @PathVariable("version_id") versionId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("template_id") val templateId: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            @param:JsonProperty("version_no") val versionNo: Int,
            val locale: String,
            val status: String,
            val category: String,
            val messages: Any,
            @param:JsonProperty("variables_schema") val variablesSchema: Any,
            @param:JsonProperty("output_schema") val outputSchema: Any,
            @param:JsonProperty("generation_policy") val generationPolicy: Any,
            @param:JsonProperty("compatibility") val compatibility: Any,
            val skills: Any,
            @param:JsonProperty("mcp_servers") val mcpServers: Any,
            val tools: Any,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("change_summary") val changeSummary: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val resolved = catalog.getVersion(templateId, versionId, userId) ?: return responseBuilder.notFound().build()
        val version = resolved.version
        val rs = Response(
            requireNotNull(version.id), templateId, resolved.template.key, resolved.template.displayName, version.versionNo, version.locale,
            version.status.name, catalog.effectiveCategory(resolved.template).name, parseJson(version.messagesJson), parseJson(version.variablesSchemaJson), parseJson(version.outputSchemaJson),
            parseJson(version.generationPolicyJson), parseJson(version.compatibilityJson), parseJson(version.skillsJson), parseJson(version.mcpServersJson), parseJson(version.toolsJson),
            version.contentHash, version.changeSummary, version.publishedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/templates/{template_id}/versions")
    fun listVersions(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
    ): ResponseEntity<Response> {
        data class VersionData(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val locale: String,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("change_summary") val changeSummary: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val list: List<VersionData>)
        val versions = catalog.listVersions(templateId, userId) ?: return responseBuilder.notFound().build()
        val rs = Response(versions.map { version ->
            VersionData(requireNotNull(version.id), version.versionNo, version.locale, version.status.name, version.contentHash, version.changeSummary, version.publishedAt, version.updatedAt)
        })
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/ai/templates")
    fun create(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("key") @NotBlank @Size(max = 100) key: String,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("purpose", defaultValue = "") @Size(max = 1000) purpose: String,
        @RequestParam("scope_type", defaultValue = "PROJECT") scopeType: TemplateScopeType,
        @RequestParam("project_id", required = false) projectId: Long?,
        @RequestParam("locale", defaultValue = "zh-CN") @Size(max = 20) locale: String,
        @RequestParam("messages_json") @NotBlank @Size(max = 500_000) messagesJson: String,
        @RequestParam("variables_schema_json") @NotBlank @Size(max = 500_000) variablesSchemaJson: String,
        @RequestParam("output_schema_json") @NotBlank @Size(max = 1_000_000) outputSchemaJson: String,
        @RequestParam("generation_policy_json", defaultValue = "{}") @Size(max = 20_000) generationPolicyJson: String,
        @RequestParam("compatibility_json", defaultValue = "{}") @Size(max = 20_000) compatibilityJson: String,
        @RequestParam("skills_json", defaultValue = "[]") @Size(max = 20_000) skillsJson: String,
        @RequestParam("mcp_servers_json", defaultValue = "[]") @Size(max = 20_000) mcpServersJson: String,
        @RequestParam("tools_json", defaultValue = "[]") @Size(max = 20_000) toolsJson: String,
        @RequestParam("change_summary", defaultValue = "") @Size(max = 1000) changeSummary: String,
        @RequestParam("category", required = false) category: TemplateCategory?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val created = try {
            catalog.createDraft(userId, key, displayName, purpose, scopeType, projectId, locale, messagesJson, variablesSchemaJson, outputSchemaJson, generationPolicyJson, compatibilityJson, skillsJson, mcpServersJson, toolsJson, changeSummary, category ?: TemplateCatalogService.CATEGORY_BY_KEY[key.trim().lowercase()] ?: TemplateCategory.SCRIPT_GENERAL)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val version = created.version
        return responseBuilder.created().data(Response(requireNotNull(created.template.id), requireNotNull(version.id), version.versionNo, version.status.name, version.contentHash)).build()
    }

    @PutMapping("/api/ai/templates/{template_id}")
    fun updateTemplate(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("purpose", defaultValue = "") @Size(max = 1000) purpose: String,
        @RequestParam("default_locale", required = false) @Size(max = 20) defaultLocale: String?,
        @RequestParam("enabled", required = false) enabled: Boolean?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val key: String,
            @param:JsonProperty("display_name") val displayName: String,
            val purpose: String,
            @param:JsonProperty("scope_type") val scopeType: String,
            @param:JsonProperty("project_id") val projectId: Long?,
            @param:JsonProperty("default_locale") val defaultLocale: String,
            val enabled: Boolean,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        val template = try {
            catalog.updateDefinition(userId, templateId, displayName, purpose, defaultLocale, enabled)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(template.id), template.key, template.displayName, template.purpose, template.scopeType.name,
            template.projectId, template.defaultLocale, template.enabled, template.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/ai/templates/{template_id}")
    fun deleteTemplate(@AuthenticationPrincipal userId: Long?, @PathVariable("template_id") templateId: Long): ResponseEntity<Response> {
        val deleted = try {
            catalog.deleteDefinition(userId, templateId)
        } catch (exception: TemplateCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        if (!deleted) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to templateId, "deleted" to true)).build()
    }

    @DeleteMapping("/api/ai/templates/{template_id}/versions/{version_id}")
    fun deleteVersion(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
        @PathVariable("version_id") versionId: Long,
    ): ResponseEntity<Response> {
        val deleted = try {
            catalog.deleteVersion(userId, templateId, versionId)
        } catch (exception: TemplateCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        if (!deleted) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to versionId, "deleted" to true)).build()
    }

    @PutMapping("/api/ai/templates/{template_id}/versions/{version_id}")
    fun update(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
        @PathVariable("version_id") versionId: Long,
        @RequestParam("locale", defaultValue = "zh-CN") @Size(max = 20) locale: String,
        @RequestParam("messages_json") @NotBlank @Size(max = 500_000) messagesJson: String,
        @RequestParam("variables_schema_json") @NotBlank @Size(max = 500_000) variablesSchemaJson: String,
        @RequestParam("output_schema_json") @NotBlank @Size(max = 1_000_000) outputSchemaJson: String,
        @RequestParam("generation_policy_json", defaultValue = "{}") @Size(max = 20_000) generationPolicyJson: String,
        @RequestParam("compatibility_json", defaultValue = "{}") @Size(max = 20_000) compatibilityJson: String,
        @RequestParam("skills_json", defaultValue = "[]") @Size(max = 20_000) skillsJson: String,
        @RequestParam("mcp_servers_json", defaultValue = "[]") @Size(max = 20_000) mcpServersJson: String,
        @RequestParam("tools_json", defaultValue = "[]") @Size(max = 20_000) toolsJson: String,
        @RequestParam("change_summary", defaultValue = "") @Size(max = 1000) changeSummary: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val updated = try {
            catalog.updateDraft(userId, templateId, versionId, locale, messagesJson, variablesSchemaJson, outputSchemaJson, generationPolicyJson, compatibilityJson, skillsJson, mcpServersJson, toolsJson, changeSummary)
        } catch (exception: TemplateCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(updated.version.id), updated.version.versionNo, updated.version.status.name, updated.version.contentHash)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions")
    fun copy(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
        @RequestParam("copy_from_version_id") @Min(1) sourceVersionId: Long,
        @RequestParam("change_summary", defaultValue = "") @Size(max = 1000) changeSummary: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val copied = try {
            catalog.copyVersion(userId, templateId, sourceVersionId, changeSummary)
        } catch (exception: TemplateCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        return responseBuilder.created().data(Response(requireNotNull(copied.version.id), copied.version.versionNo, copied.version.status.name, copied.version.contentHash)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions/{version_id}/submit-review")
    fun submitReview(@AuthenticationPrincipal userId: Long?, @PathVariable("template_id") templateId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.submitReview(userId, templateId, versionId) } catch (exception: TemplateCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions/{version_id}/reject-review")
    fun rejectReview(@AuthenticationPrincipal userId: Long?, @PathVariable("template_id") templateId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.rejectReview(userId, templateId, versionId) } catch (exception: TemplateCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions/{version_id}/publish")
    fun publish(@AuthenticationPrincipal userId: Long?, @PathVariable("template_id") templateId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.publish(userId, templateId, versionId) } catch (exception: TemplateCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions/{version_id}/archive")
    fun archive(@AuthenticationPrincipal userId: Long?, @PathVariable("template_id") templateId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.archive(userId, templateId, versionId) } catch (exception: TemplateCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions/{version_id}/rollback")
    fun rollback(@AuthenticationPrincipal userId: Long?, @PathVariable("template_id") templateId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.rollback(userId, templateId, versionId) } catch (exception: TemplateCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.created().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/templates/{template_id}/versions/{version_id}/preview")
    fun preview(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("template_id") templateId: Long,
        @PathVariable("version_id") versionId: Long,
        @RequestParam("sample_id") @NotBlank @Size(max = 100) sampleId: String,
        @RequestParam("provider_config_id", required = false) providerConfigId: Long?,
        @RequestParam("model", required = false) @Size(max = 200) model: String?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("sample_id") val sampleId: String,
            @param:JsonProperty("message_count") val messageCount: Int,
            @param:JsonProperty("template_hash") val templateHash: String,
            @param:JsonProperty("variables_hash") val variablesHash: String,
            @param:JsonProperty("rendered_input_hash") val renderedInputHash: String,
            @param:JsonProperty("input_tokens") val inputTokens: Int,
            @param:JsonProperty("variable_names") val variableNames: List<String>,
            @param:JsonProperty("provider_config_id") val providerConfigId: Long?,
            val model: String?,
        )
        val resolved = catalog.getVersion(templateId, versionId, userId) ?: return responseBuilder.notFound().build()
        return try {
            val rendered = renderer.preview(resolved, sampleId)
            responseBuilder.ok().data(Response(sampleId, rendered.messages.size, rendered.templateHash, rendered.variablesHash, rendered.renderedInputHash, rendered.inputTokens, rendered.variableNames, providerConfigId, model?.trim()?.takeIf { it.isNotBlank() })).build()
        } catch (exception: IllegalArgumentException) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    private fun parseJson(json: String): Any = runCatching { objectMapper.readTree(json) }.getOrElse { json }
}
