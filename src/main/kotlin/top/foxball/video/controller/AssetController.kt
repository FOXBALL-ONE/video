package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.AssetType
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.ai.AiAnalysisException
import top.foxball.video.ai.AiClientException
import top.foxball.video.service.AssetService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime

@Validated
@RestController
/** 项目资产及完整性检查接口。 */
class AssetController(
    private val assetService: AssetService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/assets")
    fun listEpisodeAssets(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long): ResponseEntity<Response> {
        data class AssetData(val id: Long, val name: String, val type: String, val description: String?, @param:JsonProperty("reference_url") val referenceUrl: String?, val notes: String?, val status: String, @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?)
        data class Response(val list: List<AssetData>, val completeness: Int)
        val assets = assetService.loadEpisode(projectId, episodeId) ?: return responseBuilder.notFound().build()
        val list = assets.map { AssetData(requireNotNull(it.id), it.name, it.type.name, it.description, it.referenceUrl, it.notes, it.status.name, it.updatedAt) }
        val complete = if (list.isEmpty()) 0 else list.count { it.status == AssetStatus.LOCKED.name || it.status == AssetStatus.READY.name } * 100 / list.size
        val rs = Response(list, complete)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/assets")
    fun createEpisodeAsset(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @RequestParam("name") @NotBlank @Size(max = 100) name: String, @RequestParam("type") type: EpisodeAssetType, @RequestParam("description", required = false) @Size(max = 100000) description: String?, @RequestParam("reference_url", required = false) @Size(max = 1000) referenceUrl: String?, @RequestParam("notes", required = false) @Size(max = 100000) notes: String?, @RequestParam("status", defaultValue = "DRAFT") status: AssetStatus): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, val type: String, val status: String)
        val asset = assetService.createEpisodeAsset(projectId, episodeId, name.trim(), type, description, referenceUrl, notes, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(asset.id), asset.name, asset.type.name, asset.status.name)
        return responseBuilder.created().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}")
    fun updateEpisodeAsset(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @PathVariable("asset_id") assetId: Long, @RequestParam("name") @NotBlank @Size(max = 100) name: String, @RequestParam("type") type: EpisodeAssetType, @RequestParam("description", required = false) @Size(max = 100000) description: String?, @RequestParam("reference_url", required = false) @Size(max = 1000) referenceUrl: String?, @RequestParam("notes", required = false) @Size(max = 100000) notes: String?, @RequestParam("status") status: AssetStatus): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, val type: String, val status: String)
        val asset = assetService.updateEpisodeAsset(projectId, episodeId, assetId, name.trim(), type, description, referenceUrl, notes, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(asset.id), asset.name, asset.type.name, asset.status.name)
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}")
    fun deleteEpisodeAsset(@PathVariable("project_id") projectId: Long, @PathVariable("episode_id") episodeId: Long, @PathVariable("asset_id") assetId: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)
        if (!assetService.deleteEpisodeAsset(projectId, episodeId, assetId)) return responseBuilder.notFound().build()
        val rs = Response(assetId, true)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/extract")
    fun extractEpisodeAssets(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @RequestParam("type") type: EpisodeAssetType,
        @RequestParam("provider_config_id") providerConfigId: Long,
        @RequestParam("model") @Size(max = 200) model: String,
    ): ResponseEntity<Response> {
        data class AssetData(val id: Long, val name: String, val type: String, val description: String?, val status: String)
        data class ErrorData(@param:JsonProperty("error_code") val errorCode: Int?, @param:JsonProperty("response_content") val responseContent: String?, val provider: String, @param:JsonProperty("request_id") val requestId: String)
        data class Response(val type: String, val provider: String, val model: String, @param:JsonProperty("request_id") val requestId: String, val list: List<AssetData>, val error: ErrorData? = null)
        if (providerConfigId <= 0 || model.isBlank()) return responseBuilder.badRequest().message("请提供有效的解析模型和提供商配置").build()
        val result = try {
            assetService.extractEpisodeAssets(projectId, episodeId, type, providerConfigId, model)
        } catch (exception: AiAnalysisException) {
            val error = ErrorData(exception.errorCode ?: 503, exception.responseContent, exception.provider, exception.requestId)
            return responseBuilder.serviceUnavailable().message(exception.message).data(mapOf("error" to error)).build()
        } catch (exception: IllegalArgumentException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } catch (exception: AiClientException) {
            return responseBuilder.serviceUnavailable().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val list = result.assets.map { AssetData(requireNotNull(it.id), it.name, it.type.name, it.description, it.status.name) }
        val rs = Response(type.name, result.provider, result.model, result.requestId, list)
        return responseBuilder.ok().data(rs).build()
    }
    @GetMapping("/api/projects/{project_id}/assets")
    fun listAssets(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class AssetData(val id: Long, val name: String, val type: String, val description: String?, val status: String, @param:JsonProperty("owner_id") val ownerId: Long?, @param:JsonProperty("copyright_required") val copyrightRequired: Boolean, @param:JsonProperty("copyright_status") val copyrightStatus: String, @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?)
        data class IssueData(val id: Long, @param:JsonProperty("asset_id") val assetId: Long?, @param:JsonProperty("issue_type") val issueType: String, val severity: String, val description: String, val resolved: Boolean)
        data class Response(val list: List<AssetData>, val issues: List<IssueData>, val completeness: Int)

        val snapshot = assetService.load(projectId) ?: return responseBuilder.notFound().build()
        val list = snapshot.assets.map { AssetData(requireNotNull(it.id), it.name, it.type.name, it.description, it.status.name, it.ownerId, it.copyrightRequired, it.copyrightStatus.name, it.updatedAt) }
        val issues = snapshot.issues.map { IssueData(requireNotNull(it.id), it.assetId, it.issueType.name, it.severity.name, it.description, it.resolved) }
        val complete = if (list.isEmpty()) 0 else (list.count { it.status == AssetStatus.LOCKED.name || it.status == AssetStatus.READY.name } * 100 / list.size)
        val rs = Response(list, issues, complete)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/assets")
    fun createAsset(
        @PathVariable("project_id") projectId: Long,
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("type") type: AssetType,
        @RequestParam("description", required = false) @Size(max = 100000) description: String?,
        @RequestParam("status", defaultValue = "DRAFT") status: AssetStatus,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, val type: String, val status: String)
        val asset = assetService.create(projectId, name.trim(), type, description, userId, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(asset.id), asset.name, asset.type.name, asset.status.name)
        return responseBuilder.created().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/assets/{asset_id}")
    fun updateAsset(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("asset_id") assetId: Long,
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("type") type: AssetType,
        @RequestParam("description", required = false) @Size(max = 100000) description: String?,
        @RequestParam("status") status: AssetStatus,
    ): ResponseEntity<Response> {
        data class Response(val id: Long, val name: String, val type: String, val status: String)
        val asset = assetService.update(projectId, assetId, name.trim(), type, description, status) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(asset.id), asset.name, asset.type.name, asset.status.name)
        return responseBuilder.ok().data(rs).build()
    }

    @PostMapping("/api/projects/{project_id}/assets/issues/resolve")
    fun resolveIssues(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class Response(val resolved: Int)
        if (assetService.load(projectId) == null) return responseBuilder.notFound().build()
        val rs = Response(assetService.resolveIssues(projectId))
        return responseBuilder.ok().data(rs).build()
    }
}
