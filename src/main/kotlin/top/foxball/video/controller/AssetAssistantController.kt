package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.service.AssetAssistantService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@Validated
@RestController
@PreAuthorize("isAuthenticated()")
/**
 * 资产清单助手接口。
 *
 * 助手读取当前剧集的资产清单后给出修改建议；建议只是一份待审阅的清单，用户确认后经 apply 接口写入，
 * 因此助手不会在用户没有确认的情况下改动创作数据。
 */
class AssetAssistantController(
    private val service: AssetAssistantService,
    private val responseBuilder: ResponseBuilder,
) {
    /**
     * 可选字段一律声明为可空并在服务端兜底：Jackson 不会为缺失的构造参数填 Kotlin 默认值，
     * 声明成非空会在客户端省略字段时直接抛空指针。
     */
    data class HistoryTurnRequest(
        @Size(max = 20) val role: String? = null,
        @Size(max = 20000) val content: String? = null,
    )

    data class MessageRequest(
        @param:JsonProperty("provider_config_id") @Min(1) val providerConfigId: Long,
        @NotBlank @Size(max = 200) val model: String,
        @NotBlank @Size(max = 8000) val message: String,
        @Valid @Size(max = 20) val history: List<HistoryTurnRequest>? = null,
    )

    data class ApplyItemRequest(
        @param:JsonProperty("asset_id") @Min(1) val assetId: Long,
        @NotBlank @Size(max = 60) val field: String,
        @Size(max = 100000) val value: String? = null,
        @param:JsonProperty("expected_value") @Size(max = 100000) val expectedValue: String? = null,
    )

    data class ApplyRequest(
        @Valid @Size(max = 50) val items: List<ApplyItemRequest>? = null,
    )

    private data class PatchData(
        @param:JsonProperty("asset_id") val assetId: Long,
        @param:JsonProperty("asset_name") val assetName: String,
        @param:JsonProperty("asset_type") val assetType: String,
        val field: String,
        @param:JsonProperty("field_label") val fieldLabel: String,
        val value: String,
        @param:JsonProperty("current_value") val currentValue: String?,
        val reason: String,
        val changed: Boolean,
    )

    private data class ContextData(
        @param:JsonProperty("episode_id") val episodeId: Long,
        @param:JsonProperty("episode_title") val episodeTitle: String,
        @param:JsonProperty("episode_no") val episodeNo: Int,
        @param:JsonProperty("asset_count") val assetCount: Int,
        @param:JsonProperty("ready_count") val readyCount: Int,
        @param:JsonProperty("draft_count") val draftCount: Int,
        val completeness: Int,
        @param:JsonProperty("type_counts") val typeCounts: Map<String, Int>,
        @param:JsonProperty("asset_list_truncated") val assetListTruncated: Boolean,
        @param:JsonProperty("omitted_asset_count") val omittedAssetCount: Int,
    )

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/assistant/messages")
    fun propose(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: MessageRequest,
    ): ResponseEntity<Response> {
        data class Response(
            val reply: String,
            val patches: List<PatchData>,
            val warnings: List<String>,
            val suggestions: List<String>,
            val context: ContextData,
            val provider: String,
            val model: String,
            @param:JsonProperty("request_id") val requestId: String,
            @param:JsonProperty("template_key") val templateKey: String,
            @param:JsonProperty("template_version_id") val templateVersionId: Long?,
            @param:JsonProperty("input_tokens") val inputTokens: Int?,
            @param:JsonProperty("output_tokens") val outputTokens: Int?,
        )

        val proposal = try {
            service.propose(
                projectId,
                userId,
                episodeId,
                AssetAssistantService.MessageRequest(
                    providerConfigId = request.providerConfigId,
                    model = request.model,
                    message = request.message,
                    history = request.history.orEmpty()
                        .filter { !it.content.isNullOrBlank() }
                        .map { AssetAssistantService.HistoryTurn(it.role.orEmpty(), it.content.orEmpty()) },
                ),
            )
        } catch (exception: AssetAssistantService.AssetAssistantException) {
            val status = when (exception.errorCode) {
                "AI_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS
                "AI_TIMEOUT" -> HttpStatus.GATEWAY_TIMEOUT
                else -> HttpStatus.BAD_GATEWAY
            }
            return responseBuilder.status(status).message(exception.message).data(mapOf("error_code" to exception.errorCode)).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()

        val rs = Response(
            reply = proposal.reply,
            patches = proposal.patches.map {
                PatchData(it.assetId, it.assetName, it.assetType, it.field, it.fieldLabel, it.value, it.currentValue, it.reason, it.changed)
            },
            warnings = proposal.warnings,
            suggestions = proposal.suggestions,
            context = ContextData(
                proposal.context.episodeId,
                proposal.context.episodeTitle,
                proposal.context.episodeNo,
                proposal.context.assetCount,
                proposal.context.readyCount,
                proposal.context.draftCount,
                proposal.context.completeness,
                proposal.context.typeCounts,
                proposal.context.assetListTruncated,
                proposal.context.omittedAssetCount,
            ),
            provider = proposal.provider,
            model = proposal.model,
            requestId = proposal.requestId,
            templateKey = proposal.templateKey,
            templateVersionId = proposal.templateVersionId,
            inputTokens = proposal.inputTokens,
            outputTokens = proposal.outputTokens,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/assistant/patches/apply")
    fun apply(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @AuthenticationPrincipal userId: Long?,
        @Valid @RequestBody request: ApplyRequest,
    ): ResponseEntity<Response> {
        data class ItemData(
            @param:JsonProperty("asset_id") val assetId: Long,
            val field: String,
            val status: String,
            @param:JsonProperty("code") val code: String?,
            val message: String?,
            @param:JsonProperty("current_value") val currentValue: String?,
        )
        data class Response(
            val applied: Int,
            val unchanged: Int,
            val rejected: Int,
            val items: List<ItemData>,
        )

        val result = try {
            service.applyPatches(
                projectId,
                userId,
                episodeId,
                request.items.orEmpty().map { AssetAssistantService.ApplyItem(it.assetId, it.field, it.value.orEmpty(), it.expectedValue) },
            )
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.forbidden().message("没有修改本集资产的权限").build()

        val rs = Response(
            applied = result.applied,
            unchanged = result.unchanged,
            rejected = result.rejected,
            items = result.items.map { ItemData(it.assetId, it.field, it.status, it.code, it.message, it.currentValue) },
        )
        return responseBuilder.ok().data(rs).build()
    }
}
