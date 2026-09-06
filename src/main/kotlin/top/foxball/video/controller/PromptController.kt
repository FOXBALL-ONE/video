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
import top.foxball.video.entity.jdbc.PromptScopeType
import top.foxball.video.entity.jdbc.PromptCategory
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.PromptVersionStatus
import top.foxball.video.service.PromptCatalogService
import top.foxball.video.service.PromptRenderer
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
/** 提示词目录和版本生命周期管理接口。 */
class PromptController(
    private val catalog: PromptCatalogService,
    private val renderer: PromptRenderer,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/api/ai/prompts")
    fun list(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("key", required = false) key: String?,
        @RequestParam("scope_type", required = false) scopeType: PromptScopeType?,
        @RequestParam("project_id", required = false) projectId: Long?,
        @RequestParam("status", required = false) status: PromptVersionStatus?,
        @RequestParam("category", required = false) category: PromptCategory?,
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
        val definitions = catalog.list(userId, key, scopeType, projectId, status, category)
        val versionsByPromptId = catalog.versionsByDefinitionIds(definitions.mapNotNull { it.id })
        val prompts = definitions.map { definition ->
            val versions = versionsByPromptId[definition.id].orEmpty()
            PromptData(
                requireNotNull(definition.id), definition.key, definition.displayName, definition.purpose, definition.scopeType.name, definition.projectId,
                versions.maxOfOrNull { it.versionNo }, versions.maxByOrNull { it.versionNo }?.id,
                versions.firstOrNull { it.status == PromptVersionStatus.PUBLISHED }?.versionNo,
                versions.firstOrNull { it.status == PromptVersionStatus.PUBLISHED }?.id,
                definition.defaultLocale, definition.enabled, definition.updatedAt, catalog.effectiveCategory(definition).name, definition.categoryDefault == true,
            )
        }
        return responseBuilder.ok().data(Response(prompts)).build()
    }

    @GetMapping("/api/ai/prompts/options")
    fun options(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("project_id") @Min(1) projectId: Long,
        @RequestParam("asset_type") assetType: EpisodeAssetType,
        @RequestParam("locale", defaultValue = "zh-CN") @Size(max = 20) locale: String,
    ): ResponseEntity<Response> {
        data class OptionData(
            @param:JsonProperty("prompt_id") val promptId: Long,
            @param:JsonProperty("prompt_key") val promptKey: String,
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
            catalog.scriptAssetPromptOptions(assetType, projectId, userId, locale).toMutableList()
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
                    requireNotNull(item.definition.id), item.definition.key, item.definition.displayName, item.definition.purpose,
                    item.definition.scopeType.name, item.definition.projectId, requireNotNull(version.id), version.versionNo,
                    version.status.name, category.name, version.locale, version.contentHash, "${item.definition.key}.v${version.versionNo}",
                    !fallback && requireNotNull(version.id) == defaultVersionId, fallback, if (fallback) "LEGACY_GENERAL_PROMPT" else null, version.updatedAt,
                )
            },
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}")
    fun detail(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
        @PathVariable("version_id") versionId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("prompt_id") val promptId: Long,
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
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("change_summary") val changeSummary: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val resolved = catalog.getVersion(promptId, versionId, userId) ?: return responseBuilder.notFound().build()
        val version = resolved.version
        val rs = Response(
            requireNotNull(version.id), promptId, resolved.definition.key, resolved.definition.displayName, version.versionNo, version.locale,
            version.status.name, catalog.effectiveCategory(resolved.definition).name, parseJson(version.messagesJson), parseJson(version.variablesSchemaJson), parseJson(version.outputSchemaJson),
            parseJson(version.generationPolicyJson), parseJson(version.compatibilityJson), version.contentHash, version.changeSummary, version.publishedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/ai/prompts/{prompt_id}/versions")
    fun listVersions(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
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
        val versions = catalog.listVersions(promptId, userId) ?: return responseBuilder.notFound().build()
        val rs = Response(versions.map { version ->
            VersionData(requireNotNull(version.id), version.versionNo, version.locale, version.status.name, version.contentHash, version.changeSummary, version.publishedAt, version.updatedAt)
        })
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/ai/prompts")
    fun create(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("key") @NotBlank @Size(max = 100) key: String,
        @RequestParam("display_name") @NotBlank @Size(max = 200) displayName: String,
        @RequestParam("purpose", defaultValue = "") @Size(max = 1000) purpose: String,
        @RequestParam("scope_type", defaultValue = "PROJECT") scopeType: PromptScopeType,
        @RequestParam("project_id", required = false) projectId: Long?,
        @RequestParam("locale", defaultValue = "zh-CN") @Size(max = 20) locale: String,
        @RequestParam("messages_json") @NotBlank @Size(max = 500_000) messagesJson: String,
        @RequestParam("variables_schema_json") @NotBlank @Size(max = 500_000) variablesSchemaJson: String,
        @RequestParam("output_schema_json") @NotBlank @Size(max = 1_000_000) outputSchemaJson: String,
        @RequestParam("generation_policy_json", defaultValue = "{}") @Size(max = 20_000) generationPolicyJson: String,
        @RequestParam("compatibility_json", defaultValue = "{}") @Size(max = 20_000) compatibilityJson: String,
        @RequestParam("change_summary", defaultValue = "") @Size(max = 1000) changeSummary: String,
        @RequestParam("category", required = false) category: PromptCategory?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val created = try {
            catalog.createDraft(userId, key, displayName, purpose, scopeType, projectId, locale, messagesJson, variablesSchemaJson, outputSchemaJson, generationPolicyJson, compatibilityJson, changeSummary, category ?: PromptCatalogService.CATEGORY_BY_KEY[key.trim().lowercase()] ?: PromptCategory.SCRIPT_GENERAL)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val version = created.version
        return responseBuilder.created().data(Response(requireNotNull(created.definition.id), requireNotNull(version.id), version.versionNo, version.status.name, version.contentHash)).build()
    }

    @PutMapping("/api/ai/prompts/{prompt_id}")
    fun updateDefinition(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
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
        val definition = try {
            catalog.updateDefinition(userId, promptId, displayName, purpose, defaultLocale, enabled)
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(definition.id), definition.key, definition.displayName, definition.purpose, definition.scopeType.name,
            definition.projectId, definition.defaultLocale, definition.enabled, definition.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/ai/prompts/{prompt_id}")
    fun deleteDefinition(@AuthenticationPrincipal userId: Long?, @PathVariable("prompt_id") promptId: Long): ResponseEntity<Response> {
        val deleted = try {
            catalog.deleteDefinition(userId, promptId)
        } catch (exception: PromptCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        if (!deleted) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to promptId, "deleted" to true)).build()
    }

    @DeleteMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}")
    fun deleteVersion(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
        @PathVariable("version_id") versionId: Long,
    ): ResponseEntity<Response> {
        val deleted = try {
            catalog.deleteVersion(userId, promptId, versionId)
        } catch (exception: PromptCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        if (!deleted) return responseBuilder.notFound().build()
        return responseBuilder.ok().data(mapOf("id" to versionId, "deleted" to true)).build()
    }

    @PutMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}")
    fun update(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
        @PathVariable("version_id") versionId: Long,
        @RequestParam("locale", defaultValue = "zh-CN") @Size(max = 20) locale: String,
        @RequestParam("messages_json") @NotBlank @Size(max = 500_000) messagesJson: String,
        @RequestParam("variables_schema_json") @NotBlank @Size(max = 500_000) variablesSchemaJson: String,
        @RequestParam("output_schema_json") @NotBlank @Size(max = 1_000_000) outputSchemaJson: String,
        @RequestParam("generation_policy_json", defaultValue = "{}") @Size(max = 20_000) generationPolicyJson: String,
        @RequestParam("compatibility_json", defaultValue = "{}") @Size(max = 20_000) compatibilityJson: String,
        @RequestParam("change_summary", defaultValue = "") @Size(max = 1000) changeSummary: String,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
        )
        val updated = try {
            catalog.updateDraft(userId, promptId, versionId, locale, messagesJson, variablesSchemaJson, outputSchemaJson, generationPolicyJson, compatibilityJson, changeSummary)
        } catch (exception: PromptCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(updated.version.id), updated.version.versionNo, updated.version.status.name, updated.version.contentHash)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions")
    fun copy(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
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
            catalog.copyVersion(userId, promptId, sourceVersionId, changeSummary)
        } catch (exception: PromptCatalogService.ConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        return responseBuilder.created().data(Response(requireNotNull(copied.version.id), copied.version.versionNo, copied.version.status.name, copied.version.contentHash)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}/submit-review")
    fun submitReview(@AuthenticationPrincipal userId: Long?, @PathVariable("prompt_id") promptId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.submitReview(userId, promptId, versionId) } catch (exception: PromptCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}/reject-review")
    fun rejectReview(@AuthenticationPrincipal userId: Long?, @PathVariable("prompt_id") promptId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.rejectReview(userId, promptId, versionId) } catch (exception: PromptCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}/publish")
    fun publish(@AuthenticationPrincipal userId: Long?, @PathVariable("prompt_id") promptId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.publish(userId, promptId, versionId) } catch (exception: PromptCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}/archive")
    fun archive(@AuthenticationPrincipal userId: Long?, @PathVariable("prompt_id") promptId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.archive(userId, promptId, versionId) } catch (exception: PromptCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}/rollback")
    fun rollback(@AuthenticationPrincipal userId: Long?, @PathVariable("prompt_id") promptId: Long, @PathVariable("version_id") versionId: Long): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("version_no") val versionNo: Int,
            val status: String,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("published_at") val publishedAt: LocalDateTime?,
        )
        val result = try { catalog.rollback(userId, promptId, versionId) } catch (exception: PromptCatalogService.ConflictException) { return responseBuilder.status(HttpStatus.CONFLICT).message(exception.message).build() } catch (exception: IllegalArgumentException) { return responseBuilder.badRequest().message(exception.message).build() } ?: return responseBuilder.notFound().build()
        return responseBuilder.created().data(Response(requireNotNull(result.version.id), result.version.versionNo, result.version.status.name, result.version.contentHash, result.version.publishedAt)).build()
    }

    @PostMapping("/api/ai/prompts/{prompt_id}/versions/{version_id}/preview")
    fun preview(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("prompt_id") promptId: Long,
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
        val resolved = catalog.getVersion(promptId, versionId, userId) ?: return responseBuilder.notFound().build()
        return try {
            val rendered = renderer.preview(resolved, sampleId)
            responseBuilder.ok().data(Response(sampleId, rendered.messages.size, rendered.templateHash, rendered.variablesHash, rendered.renderedInputHash, rendered.inputTokens, rendered.variableNames, providerConfigId, model?.trim()?.takeIf { it.isNotBlank() })).build()
        } catch (exception: IllegalArgumentException) {
            responseBuilder.badRequest().message(exception.message).build()
        }
    }

    private fun parseJson(json: String): Any = runCatching { objectMapper.readTree(json) }.getOrElse { json }
}
