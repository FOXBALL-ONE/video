package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiAnalysisException
import top.foxball.video.service.ScriptService
import top.foxball.video.service.ScriptAnalysisTaskService
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

@Validated
@RestController
/** 剧本输入接口，负责当前剧本版本及结构化结果。 */
class ScriptController(
    private val scriptService: ScriptService,
    private val scriptAnalysisTaskService: ScriptAnalysisTaskService,
    private val responseBuilder: ResponseBuilder,
    private val objectMapper: ObjectMapper,
) {
    @GetMapping("/api/projects/{project_id}/script")
    fun getScript(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class VersionData(val id: Long, @param:JsonProperty("version_no") val versionNo: Int, @param:JsonProperty("version_name") val versionName: String, val status: String, @param:JsonProperty("created_at") val createdAt: LocalDateTime?)
        data class SceneData(val id: Long, @param:JsonProperty("scene_no") val sceneNo: Int, val title: String, val location: String?, @param:JsonProperty("time_of_day") val timeOfDay: String?, @param:JsonProperty("duration_seconds") val durationSeconds: Int, val content: String?)
        data class CharacterData(val id: Long, val name: String, val description: String?, val motivation: String?, val relationship: String?)
        data class DialogueData(val id: Long, @param:JsonProperty("script_scene_id") val scriptSceneId: Long, @param:JsonProperty("character_id") val characterId: Long?, @param:JsonProperty("sequence_no") val sequenceNo: Int, val content: String, @param:JsonProperty("dialogue_type") val dialogueType: String, @param:JsonProperty("duration_seconds") val durationSeconds: Int)
        data class QualityData(val id: Long, @param:JsonProperty("rule_code") val ruleCode: String, val title: String, val note: String?, val passed: Boolean, val score: BigDecimal?)
        data class AnalysisData(@param:JsonProperty("status") val status: String, @param:JsonProperty("provider_config_id") val providerConfigId: Long?, val model: String?, @param:JsonProperty("analyzed_at") val analyzedAt: LocalDateTime?, val result: JsonNode?)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, val title: String, val version: VersionData, val versions: List<VersionData>, val content: String, val scenes: List<SceneData>, val characters: List<CharacterData>, val dialogues: List<DialogueData>, @param:JsonProperty("quality_checks") val qualityChecks: List<QualityData>, val stats: Map<String, Any?>, val analysis: AnalysisData)

        val snapshot = scriptService.load(projectId, episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(snapshot.document.id), snapshot.document.title,
            VersionData(requireNotNull(snapshot.version.id), snapshot.version.versionNo, snapshot.version.versionName, snapshot.version.status.name, snapshot.version.createdAt),
            snapshot.versions.map { VersionData(requireNotNull(it.id), it.versionNo, it.versionName, it.status.name, it.createdAt) },
            snapshot.version.content,
            snapshot.scenes.map { SceneData(requireNotNull(it.id), it.sceneNo, it.title, it.location, it.timeOfDay, it.durationSeconds, it.content) },
            snapshot.characters.map { CharacterData(requireNotNull(it.id), it.name, it.description, it.motivation, it.relationship) },
            snapshot.dialogues.map { DialogueData(requireNotNull(it.id), it.scriptSceneId, it.characterId, it.sequenceNo, it.content, it.dialogueType.name, it.durationSeconds) },
            snapshot.qualityChecks.map { QualityData(requireNotNull(it.id), it.ruleCode, it.title, it.note, it.passed, it.score) },
            mapOf("scene_count" to snapshot.version.sceneCount, "character_count" to snapshot.version.characterCount, "dialogue_count" to snapshot.version.dialogueCount, "estimated_duration_seconds" to snapshot.version.estimatedDurationSeconds, "structure_score" to snapshot.version.structureScore),
            AnalysisData(snapshot.version.aiAnalysisStatus.name, snapshot.version.aiAnalysisProviderConfigId, snapshot.version.aiAnalysisModel, snapshot.version.aiAnalysisAt, snapshot.version.aiAnalysisJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }),
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/script/parse")
    fun analyzeScript(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @Size(max = 200) model: String,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class ErrorData(@param:JsonProperty("error_code") val errorCode: Int?, @param:JsonProperty("response_content") val responseContent: String?, val provider: String, @param:JsonProperty("request_id") val requestId: String)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, @param:JsonProperty("version_id") val versionId: Long, @param:JsonProperty("analysis_status") val analysisStatus: String, val result: JsonNode?, val error: ErrorData? = null)
        if (model.isBlank() || providerConfigId <= 0) return responseBuilder.badRequest().message("请提供有效的解析模型和提供商配置").build()
        if (episodeId != null) return responseBuilder.badRequest().message("剧集剧本请使用异步资产解析任务").build()
        val snapshot = try {
            scriptService.analyze(projectId, episodeId, providerConfigId, model)
        } catch (exception: AiAnalysisException) {
            val error = ErrorData(exception.errorCode ?: 503, null, exception.provider, exception.requestId)
            return responseBuilder.serviceUnavailable().message(exception.message).data(mapOf("error" to error)).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } catch (exception: AiClientException) {
            return responseBuilder.serviceUnavailable().message(exception.message).build()
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
            ?: return responseBuilder.notFound().build()
        val result = snapshot.version.aiAnalysisJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        val rs = Response(requireNotNull(snapshot.document.id), requireNotNull(snapshot.version.id), snapshot.version.aiAnalysisStatus.name, result)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/script/analysis-tasks")
    fun submitAnalysisTask(
        @PathVariable("project_id") projectId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestHeader("Idempotency-Key", required = false) @Size(max = 200) idempotencyKey: String?,
        @RequestParam("episode_id") @Min(1) episodeId: Long,
        @RequestParam("version_id") @Min(1) versionId: Long,
        @RequestParam("content_hash") @NotBlank @Size(max = 80) contentHash: String,
        @RequestParam("character_provider_config_id", required = false) characterProviderConfigId: Long?,
        @RequestParam("character_model", required = false) @Size(max = 200) characterModel: String?,
        @RequestParam("scene_background_provider_config_id", required = false) sceneBackgroundProviderConfigId: Long?,
        @RequestParam("scene_background_model", required = false) @Size(max = 200) sceneBackgroundModel: String?,
        @RequestParam("prop_provider_config_id", required = false) propProviderConfigId: Long?,
        @RequestParam("prop_model", required = false) @Size(max = 200) propModel: String?,
        @RequestParam("asset_types", required = false) legacyAssetTypes: List<String>?,
        @RequestParam("provider_config_ids", required = false) legacyProviderConfigIds: List<Long>?,
        @RequestParam("models", required = false) legacyModels: List<String>?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            val status: String,
            @param:JsonProperty("asset_types") val assetTypes: List<String>,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("correlation_id") val correlationId: String?,
        )
        val selections = mutableListOf<ScriptAnalysisTaskService.ModelSelection>()
        val hasNamedParameters = characterProviderConfigId != null || characterModel != null ||
            sceneBackgroundProviderConfigId != null || sceneBackgroundModel != null ||
            propProviderConfigId != null || propModel != null
        val hasLegacyParameters = legacyAssetTypes != null || legacyProviderConfigIds != null || legacyModels != null
        if (hasNamedParameters && hasLegacyParameters) return responseBuilder.badRequest().message("新旧解析参数不能混用").build()
        if (hasLegacyParameters) {
            val types = legacyAssetTypes.orEmpty()
            val providerIds = legacyProviderConfigIds.orEmpty()
            val models = legacyModels.orEmpty()
            if (types.isEmpty() || types.size != providerIds.size || types.size != models.size) {
                return responseBuilder.badRequest().message("旧版解析参数必须按类型、提供商和模型一一对应").build()
            }
            types.forEachIndexed { index, value ->
                val type = runCatching { EpisodeAssetType.valueOf(value.trim().uppercase()) }.getOrNull()
                    ?: return responseBuilder.badRequest().message("不支持的资产类型：$value").build()
                val providerId = providerIds[index]
                val model = models[index].trim()
                if (type !in setOf(EpisodeAssetType.CHARACTER, EpisodeAssetType.SCENE_BACKGROUND, EpisodeAssetType.PROP) || providerId <= 0 || model.isBlank()) {
                    return responseBuilder.badRequest().message("旧版解析参数不完整").build()
                }
                selections += ScriptAnalysisTaskService.ModelSelection(type, providerId, model)
            }
        }
        if (characterProviderConfigId != null || characterModel != null) {
            if (characterProviderConfigId == null || characterProviderConfigId <= 0 || characterModel.isNullOrBlank()) return responseBuilder.badRequest().message("请为角色选择有效的提供商和模型").build()
            selections += ScriptAnalysisTaskService.ModelSelection(EpisodeAssetType.CHARACTER, characterProviderConfigId, characterModel.trim())
        }
        if (sceneBackgroundProviderConfigId != null || sceneBackgroundModel != null) {
            if (sceneBackgroundProviderConfigId == null || sceneBackgroundProviderConfigId <= 0 || sceneBackgroundModel.isNullOrBlank()) return responseBuilder.badRequest().message("请为场景选择有效的提供商和模型").build()
            selections += ScriptAnalysisTaskService.ModelSelection(EpisodeAssetType.SCENE_BACKGROUND, sceneBackgroundProviderConfigId, sceneBackgroundModel.trim())
        }
        if (propProviderConfigId != null || propModel != null) {
            if (propProviderConfigId == null || propProviderConfigId <= 0 || propModel.isNullOrBlank()) return responseBuilder.badRequest().message("请为道具选择有效的提供商和模型").build()
            selections += ScriptAnalysisTaskService.ModelSelection(EpisodeAssetType.PROP, propProviderConfigId, propModel.trim())
        }
        if (selections.isEmpty()) return responseBuilder.badRequest().message("请至少选择一种资产类型").build()
        val submitted = try {
            scriptAnalysisTaskService.submit(projectId, userId, episodeId, versionId, contentHash, selections, idempotencyKey)
        } catch (_: ScriptAnalysisTaskService.QueueFullException) {
            return responseBuilder.tooManyRequests().retryAfter(2).message("解析任务队列已满，请稍后重试").build()
        } catch (_: ScriptAnalysisTaskService.ContentConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message("剧本内容已变化，请重新解析当前版本").build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val task = submitted.task
        val rs = Response(requireNotNull(task.id).toString(), task.status.name, selections.map { it.assetType.name }, versionId, task.correlationId)
        return responseBuilder.accepted().data(rs).build()
    }

    @GetMapping("/api/projects/{project_id}/script/analysis-tasks/{task_id}")
    fun getAnalysisTask(
        @PathVariable("project_id") projectId: Long,
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("task_id") taskId: Long,
    ): ResponseEntity<Response> {
        data class ErrorData(
            val message: String,
            @param:JsonProperty("error_code") val errorCode: String?,
            val provider: String?,
            @param:JsonProperty("request_id") val requestId: String?,
        )
        data class TypeResultData(
            val type: String,
            val status: String,
            @param:JsonProperty("provider_config_id") val providerConfigId: Long?,
            val model: String?,
            val provider: String?,
            @param:JsonProperty("request_id") val requestId: String?,
            val count: Int,
            @param:JsonProperty("result_hash") val resultHash: String?,
            val error: ErrorData?,
        )
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            val status: String,
            val progress: Int,
            val stage: String?,
            @param:JsonProperty("result_status") val resultStatus: String,
            @param:JsonProperty("asset_types") val assetTypes: List<String>,
            @param:JsonProperty("type_results") val typeResults: List<TypeResultData>,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("content_hash") val contentHash: String,
            @param:JsonProperty("adopted_types") val adoptedTypes: List<String>,
            val result: JsonNode?,
            val stats: Map<String, Int>,
            val error: ErrorData?,
            @param:JsonProperty("adopted_at") val adoptedAt: LocalDateTime?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("completed_at") val completedAt: LocalDateTime?,
        )
        val detail = scriptAnalysisTaskService.detail(projectId, userId, taskId) ?: return responseBuilder.notFound().build()
        val task = detail.task
        val typeResultsNode = runCatching { objectMapper.readTree(detail.result.typeResultsJson) }.getOrNull()
        val assetTypes = (task.inputPayload["asset_types"] as? Collection<*>)?.mapNotNull { it?.toString() }.orEmpty()
        val typeResults = assetTypes.map { type ->
            val typeResult = typeResultsNode?.get(type)
            val status = typeResult?.get("status")?.takeIf { it.isString }?.stringValue() ?: "PENDING"
            val errorMessage = typeResult?.get("errorMessage")?.takeIf { it.isString }?.stringValue()
                ?: typeResult?.get("error_message")?.takeIf { it.isString }?.stringValue()
            val error = errorMessage?.let { message ->
                ErrorData(
                    message,
                    typeResult?.get("errorCode")?.takeIf { it.isString }?.stringValue()
                        ?: typeResult?.get("error_code")?.takeIf { it.isString }?.stringValue(),
                    typeResult?.get("provider")?.takeIf { it.isString }?.stringValue(),
                    typeResult?.get("requestId")?.takeIf { it.isString }?.stringValue()
                        ?: typeResult?.get("request_id")?.takeIf { it.isString }?.stringValue(),
                )
            }
            TypeResultData(
                type,
                status,
                (typeResult?.get("providerConfigId")?.takeIf { it.isInt || it.isLong }?.longValue()?.takeIf { it > 0 }
                    ?: typeResult?.get("provider_config_id")?.takeIf { it.isInt || it.isLong }?.longValue()?.takeIf { it > 0 }),
                typeResult?.get("model")?.takeIf { it.isString }?.stringValue()
                    ?: typeResult?.get("model_name")?.takeIf { it.isString }?.stringValue(),
                typeResult?.get("provider")?.takeIf { it.isString }?.stringValue(),
                typeResult?.get("requestId")?.takeIf { it.isString }?.stringValue()
                    ?: typeResult?.get("request_id")?.takeIf { it.isString }?.stringValue(),
                typeResult?.get("items")?.takeIf { it.isArray }?.size() ?: 0,
                typeResult?.get("resultHash")?.takeIf { it.isString }?.stringValue()
                    ?: typeResult?.get("result_hash")?.takeIf { it.isString }?.stringValue(),
                error,
            )
        }
        val result = detail.result.resultJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() }
        val stats = mapOf(
            "character_count" to (result?.path("characters")?.takeIf { it.isArray }?.size() ?: 0),
            "scene_count" to (result?.path("scenes")?.takeIf { it.isArray }?.size() ?: 0),
            "prop_count" to (result?.path("props")?.takeIf { it.isArray }?.size() ?: 0),
        )
        val failedType = typeResults.firstOrNull { it.status == "FAILED" }
        val error = task.errorMessage?.let { ErrorData(it, task.errorCode, failedType?.provider, failedType?.requestId) }
            ?: failedType?.error
        val rs = Response(
            requireNotNull(task.id).toString(), task.status.name, task.progress, task.stage, detail.resultStatus.name,
            assetTypes, typeResults, detail.result.scriptVersionId, detail.result.contentHash, detail.result.adoptedTypes.split(',').filter { it.isNotBlank() },
            result, stats, error, detail.result.adoptedAt, task.createdAt, task.completedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/script/analysis-tasks/{task_id}/adopt")
    fun adoptAnalysisTask(
        @PathVariable("project_id") projectId: Long,
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("task_id") taskId: Long,
        @RequestHeader("Idempotency-Key", required = false) @Size(max = 200) idempotencyKey: String?,
        @RequestParam("version_id") @Min(1) versionId: Long,
        @RequestParam("selected_asset_types", required = false) selectedAssetTypes: List<EpisodeAssetType>?,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            @param:JsonProperty("task_status") val taskStatus: String,
            @param:JsonProperty("adoption_status") val adoptionStatus: String,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("selected_asset_types") val selectedAssetTypes: List<String>,
            @param:JsonProperty("created_count") val createdCount: Int,
            @param:JsonProperty("updated_count") val updatedCount: Int,
            @param:JsonProperty("skipped_count") val skippedCount: Int,
            @param:JsonProperty("conflict_count") val conflictCount: Int,
            @param:JsonProperty("missing_name_count") val missingNameCount: Int,
            @param:JsonProperty("asset_ids") val assetIds: List<Long>,
            @param:JsonProperty("adopted_at") val adoptedAt: LocalDateTime?,
        )
        val adopted = try {
            scriptAnalysisTaskService.adopt(projectId, userId, taskId, versionId, selectedAssetTypes.orEmpty(), idempotencyKey)
        } catch (_: ScriptAnalysisTaskService.ContentConflictException) {
            return responseBuilder.status(HttpStatus.CONFLICT).message("剧本内容已变化，请重新解析当前版本").build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(adopted.task.id).toString(), adopted.task.status.name, adopted.result.resultStatus.name, adopted.result.scriptVersionId,
            adopted.selectedTypes.map { it.name }, adopted.createdCount, 0, adopted.conflictCount + adopted.missingNameCount,
            adopted.conflictCount, adopted.missingNameCount, adopted.createdAssetIds, adopted.result.adoptedAt ?: adopted.result.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/script/analysis-tasks/{task_id}/discard")
    fun discardAnalysisTask(
        @PathVariable("project_id") projectId: Long,
        @AuthenticationPrincipal userId: Long?,
        @PathVariable("task_id") taskId: Long,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("task_id") val taskId: String,
            @param:JsonProperty("task_status") val taskStatus: String,
            @param:JsonProperty("result_status") val resultStatus: String,
            @param:JsonProperty("version_id") val versionId: Long,
            @param:JsonProperty("discarded_at") val discardedAt: LocalDateTime?,
        )
        val discarded = try {
            scriptAnalysisTaskService.discard(projectId, userId, taskId)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(discarded.task.id).toString(), discarded.task.status.name,
            discarded.result.resultStatus.name, discarded.result.scriptVersionId, discarded.result.discardedAt ?: discarded.result.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/script")
    fun saveScript(
        @PathVariable("project_id") projectId: Long,
        @RequestBody @Size(max = 2_000_000) content: String,
        @RequestParam("title", required = false) title: String?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class VersionData(val id: Long, @param:JsonProperty("version_no") val versionNo: Int, @param:JsonProperty("version_name") val versionName: String, val status: String, @param:JsonProperty("created_at") val createdAt: LocalDateTime?)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, val title: String, val version: VersionData, val content: String, val stats: Map<String, Any?>)

        val snapshot = scriptService.save(projectId, content, title, episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(
            requireNotNull(snapshot.document.id), snapshot.document.title,
            VersionData(requireNotNull(snapshot.version.id), snapshot.version.versionNo, snapshot.version.versionName, snapshot.version.status.name, snapshot.version.createdAt),
            snapshot.version.content,
            mapOf("scene_count" to snapshot.version.sceneCount, "character_count" to snapshot.version.characterCount, "dialogue_count" to snapshot.version.dialogueCount, "estimated_duration_seconds" to snapshot.version.estimatedDurationSeconds, "structure_score" to snapshot.version.structureScore),
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/script/lock")
    fun lockScript(
        @PathVariable("project_id") projectId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestParam("episode_id", required = false) episodeId: Long?,
    ): ResponseEntity<Response> {
        data class VersionData(val id: Long, @param:JsonProperty("version_no") val versionNo: Int, @param:JsonProperty("version_name") val versionName: String, val status: String, @param:JsonProperty("locked_at") val lockedAt: LocalDateTime?)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, val title: String, val version: VersionData, val content: String)

        val snapshot = scriptService.lock(projectId, userId, episodeId) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(snapshot.document.id), snapshot.document.title, VersionData(requireNotNull(snapshot.version.id), snapshot.version.versionNo, snapshot.version.versionName, snapshot.version.status.name, snapshot.version.lockedAt), snapshot.version.content)
        return responseBuilder.ok().data(rs).build()
    }
}
