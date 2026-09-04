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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.AssetType
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.service.AssetService
import top.foxball.video.service.FileService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.time.LocalDateTime
import java.util.UUID

@Validated
@RestController
/** 项目资产及完整性检查接口。 */
class AssetController(
    private val assetService: AssetService,
    private val fileService: FileService,
    private val responseBuilder: ResponseBuilder,
) {
    private data class CharacterImageData(@param:JsonProperty("file_id") val fileId: String?, val url: String?)

    data class CharacterProfileRequest(
        val name: String? = null,
        val description: String? = null,
        @param:JsonProperty("reference_url") val referenceUrl: String? = null,
        val status: AssetStatus? = null,
        @param:JsonProperty("identity_title") val identityTitle: String? = null,
        @param:JsonProperty("role_tag") val roleTag: String? = null,
        val gender: String? = null,
        @param:JsonProperty("age_stage") val ageStage: String? = null,
        @param:JsonProperty("age_note") val ageNote: String? = null,
        @param:JsonProperty("is_real_person") val isRealPerson: Boolean = false,
        @param:JsonProperty("face_description") val faceDescription: String? = null,
        @param:JsonProperty("costume_description") val costumeDescription: String? = null,
        @param:JsonProperty("three_view_front_file_id") val threeViewFrontFileId: String? = null,
        @param:JsonProperty("three_view_side_file_id") val threeViewSideFileId: String? = null,
        @param:JsonProperty("three_view_back_file_id") val threeViewBackFileId: String? = null,
        @param:JsonProperty("three_view_merged_file_id") val threeViewMergedFileId: String? = null,
        @param:JsonProperty("expression_file_ids") val expressionFileIds: List<String> = emptyList(),
        val hairstyle: String? = null,
        @param:JsonProperty("costume_ref") val costumeRef: String? = null,
        @param:JsonProperty("accessory_ref") val accessoryRef: String? = null,
        @param:JsonProperty("voice_asset_id") val voiceAssetId: Long? = null,
    )

    private data class CharacterProfileData(
        @param:JsonProperty("asset_id") val assetId: Long,
        val name: String,
        val description: String?,
        @param:JsonProperty("reference_url") val referenceUrl: String?,
        val status: String,
        @param:JsonProperty("identity_title") val identityTitle: String?,
        @param:JsonProperty("role_tag") val roleTag: String?,
        val gender: String?,
        @param:JsonProperty("age_stage") val ageStage: String?,
        @param:JsonProperty("age_note") val ageNote: String?,
        @param:JsonProperty("is_real_person") val isRealPerson: Boolean,
        @param:JsonProperty("face_description") val faceDescription: String?,
        @param:JsonProperty("costume_description") val costumeDescription: String?,
        @param:JsonProperty("three_view_front") val threeViewFront: CharacterImageData?,
        @param:JsonProperty("three_view_side") val threeViewSide: CharacterImageData?,
        @param:JsonProperty("three_view_back") val threeViewBack: CharacterImageData?,
        @param:JsonProperty("three_view_merged") val threeViewMerged: CharacterImageData?,
        @param:JsonProperty("expressions") val expressions: List<CharacterImageData>,
        val hairstyle: String?,
        @param:JsonProperty("costume_ref") val costumeRef: String?,
        @param:JsonProperty("accessory_ref") val accessoryRef: String?,
        @param:JsonProperty("voice_asset_id") val voiceAssetId: Long?,
        @param:JsonProperty("voice_asset_name") val voiceAssetName: String?,
        @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
    )

    private fun resolveCharacterImage(userId: Long?, fileId: String?): CharacterImageData? {
        val id = fileId?.trim()?.ifBlank { null } ?: return null
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return CharacterImageData(id, null)
        val url = userId?.let { owner -> runCatching { fileService.createDownloadLinks(owner, listOf(uuid)).first().signedDownloadUrl }.getOrNull() }
        return CharacterImageData(id, url)
    }

    private fun buildCharacterProfileData(userId: Long?, view: AssetService.CharacterProfileView): CharacterProfileData {
        val profile = view.profile
        return CharacterProfileData(
            assetId = requireNotNull(view.asset.id),
            name = view.asset.name,
            description = view.asset.description,
            referenceUrl = view.asset.referenceUrl,
            status = view.asset.status.name,
            identityTitle = profile.identityTitle,
            roleTag = profile.roleTag,
            gender = profile.gender,
            ageStage = profile.ageStage,
            ageNote = profile.ageNote,
            isRealPerson = profile.isRealPerson,
            faceDescription = profile.faceDescription,
            costumeDescription = profile.costumeDescription,
            threeViewFront = resolveCharacterImage(userId, profile.threeViewFrontFileId),
            threeViewSide = resolveCharacterImage(userId, profile.threeViewSideFileId),
            threeViewBack = resolveCharacterImage(userId, profile.threeViewBackFileId),
            threeViewMerged = resolveCharacterImage(userId, profile.threeViewMergedFileId),
            expressions = view.expressionFileIdList.mapNotNull { fileId -> resolveCharacterImage(userId, fileId) },
            hairstyle = profile.hairstyle,
            costumeRef = profile.costumeRef,
            accessoryRef = profile.accessoryRef,
            voiceAssetId = profile.voiceAssetId,
            voiceAssetName = view.voiceAssetName,
            updatedAt = profile.updatedAt,
        )
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/character-profile")
    fun getCharacterProfile(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        val view = assetService.loadCharacterProfile(projectId, episodeId, assetId) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(buildCharacterProfileData(userId, view)).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/character-profile")
    fun updateCharacterProfile(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestBody request: CharacterProfileRequest,
    ): ResponseEntity<Response> {
        data class Response(val data: CharacterProfileData)
        val command = AssetService.CharacterProfileCommand(
            name = request.name,
            description = request.description,
            referenceUrl = request.referenceUrl,
            status = request.status,
            identityTitle = request.identityTitle,
            roleTag = request.roleTag,
            gender = request.gender,
            ageStage = request.ageStage,
            ageNote = request.ageNote,
            isRealPerson = request.isRealPerson,
            faceDescription = request.faceDescription,
            costumeDescription = request.costumeDescription,
            threeViewFrontFileId = request.threeViewFrontFileId,
            threeViewSideFileId = request.threeViewSideFileId,
            threeViewBackFileId = request.threeViewBackFileId,
            threeViewMergedFileId = request.threeViewMergedFileId,
            expressionFileIds = request.expressionFileIds,
            hairstyle = request.hairstyle,
            costumeRef = request.costumeRef,
            accessoryRef = request.accessoryRef,
            voiceAssetId = request.voiceAssetId,
        )
        val view = assetService.saveCharacterProfile(projectId, episodeId, assetId, command) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(buildCharacterProfileData(userId, view)).build()
    }

    data class SceneProfileRequest(
        @param:JsonProperty("identity_title") val identityTitle: String? = null,
        val location: String? = null,
        val time: String? = null,
        val atmosphere: String? = null,
        val description: String? = null,
        @param:JsonProperty("main_image_file_id") val mainImageFileId: String? = null,
        @param:JsonProperty("view_east_file_id") val viewEastFileId: String? = null,
        @param:JsonProperty("view_south_file_id") val viewSouthFileId: String? = null,
        @param:JsonProperty("view_west_file_id") val viewWestFileId: String? = null,
        @param:JsonProperty("view_north_file_id") val viewNorthFileId: String? = null,
        @param:JsonProperty("angle_long_shot_file_id") val angleLongShotFileId: String? = null,
        @param:JsonProperty("angle_full_shot_file_id") val angleFullShotFileId: String? = null,
        @param:JsonProperty("angle_medium_shot_file_id") val angleMediumShotFileId: String? = null,
        @param:JsonProperty("angle_close_shot_file_id") val angleCloseShotFileId: String? = null,
        @param:JsonProperty("light_morning_file_id") val lightMorningFileId: String? = null,
        @param:JsonProperty("light_afternoon_file_id") val lightAfternoonFileId: String? = null,
        @param:JsonProperty("light_night_file_id") val lightNightFileId: String? = null,
    )

    private data class SceneProfileData(
        @param:JsonProperty("asset_id") val assetId: Long,
        val name: String,
        @param:JsonProperty("identity_title") val identityTitle: String?,
        val location: String?,
        val time: String?,
        val atmosphere: String?,
        val description: String?,
        @param:JsonProperty("main_image") val mainImage: CharacterImageData?,
        @param:JsonProperty("view_east") val viewEast: CharacterImageData?,
        @param:JsonProperty("view_south") val viewSouth: CharacterImageData?,
        @param:JsonProperty("view_west") val viewWest: CharacterImageData?,
        @param:JsonProperty("view_north") val viewNorth: CharacterImageData?,
        @param:JsonProperty("angle_long_shot") val angleLongShot: CharacterImageData?,
        @param:JsonProperty("angle_full_shot") val angleFullShot: CharacterImageData?,
        @param:JsonProperty("angle_medium_shot") val angleMediumShot: CharacterImageData?,
        @param:JsonProperty("angle_close_shot") val angleCloseShot: CharacterImageData?,
        @param:JsonProperty("light_morning") val lightMorning: CharacterImageData?,
        @param:JsonProperty("light_afternoon") val lightAfternoon: CharacterImageData?,
        @param:JsonProperty("light_night") val lightNight: CharacterImageData?,
        @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
    )

    private fun buildSceneProfileData(userId: Long?, view: AssetService.SceneProfileView): SceneProfileData {
        val profile = view.profile
        return SceneProfileData(
            assetId = requireNotNull(view.asset.id),
            name = view.asset.name,
            identityTitle = profile.identityTitle,
            location = profile.location,
            time = profile.timeNote,
            atmosphere = profile.atmosphere,
            description = view.asset.description,
            mainImage = resolveCharacterImage(userId, profile.mainImageFileId),
            viewEast = resolveCharacterImage(userId, profile.viewEastFileId),
            viewSouth = resolveCharacterImage(userId, profile.viewSouthFileId),
            viewWest = resolveCharacterImage(userId, profile.viewWestFileId),
            viewNorth = resolveCharacterImage(userId, profile.viewNorthFileId),
            angleLongShot = resolveCharacterImage(userId, profile.angleLongShotFileId),
            angleFullShot = resolveCharacterImage(userId, profile.angleFullShotFileId),
            angleMediumShot = resolveCharacterImage(userId, profile.angleMediumShotFileId),
            angleCloseShot = resolveCharacterImage(userId, profile.angleCloseShotFileId),
            lightMorning = resolveCharacterImage(userId, profile.lightMorningFileId),
            lightAfternoon = resolveCharacterImage(userId, profile.lightAfternoonFileId),
            lightNight = resolveCharacterImage(userId, profile.lightNightFileId),
            updatedAt = profile.updatedAt,
        )
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/scene-profile")
    fun getSceneProfile(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        val view = assetService.loadSceneProfile(projectId, episodeId, assetId) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(buildSceneProfileData(userId, view)).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/scene-profile")
    fun updateSceneProfile(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestBody request: SceneProfileRequest,
    ): ResponseEntity<Response> {
        val command = AssetService.SceneProfileCommand(
            identityTitle = request.identityTitle,
            location = request.location,
            time = request.time,
            atmosphere = request.atmosphere,
            description = request.description,
            mainImageFileId = request.mainImageFileId,
            viewEastFileId = request.viewEastFileId,
            viewSouthFileId = request.viewSouthFileId,
            viewWestFileId = request.viewWestFileId,
            viewNorthFileId = request.viewNorthFileId,
            angleLongShotFileId = request.angleLongShotFileId,
            angleFullShotFileId = request.angleFullShotFileId,
            angleMediumShotFileId = request.angleMediumShotFileId,
            angleCloseShotFileId = request.angleCloseShotFileId,
            lightMorningFileId = request.lightMorningFileId,
            lightAfternoonFileId = request.lightAfternoonFileId,
            lightNightFileId = request.lightNightFileId,
        )
        val view = assetService.saveSceneProfile(projectId, episodeId, assetId, command) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(buildSceneProfileData(userId, view)).build()
    }

    data class PropProfileRequest(
        @param:JsonProperty("identity_title") val identityTitle: String? = null,
        val category: String? = null,
        @param:JsonProperty("plot_function") val plotFunction: String? = null,
        val description: String? = null,
        @param:JsonProperty("concept_image_file_id") val conceptImageFileId: String? = null,
    )

    private data class PropProfileData(
        @param:JsonProperty("asset_id") val assetId: Long,
        val name: String,
        @param:JsonProperty("identity_title") val identityTitle: String?,
        val category: String?,
        @param:JsonProperty("plot_function") val plotFunction: String?,
        val description: String?,
        @param:JsonProperty("concept_image") val conceptImage: CharacterImageData?,
        @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
    )

    private fun buildPropProfileData(userId: Long?, view: AssetService.PropProfileView): PropProfileData {
        val profile = view.profile
        return PropProfileData(
            assetId = requireNotNull(view.asset.id),
            name = view.asset.name,
            identityTitle = profile.identityTitle,
            category = profile.category,
            plotFunction = profile.plotFunction,
            description = view.asset.description,
            conceptImage = resolveCharacterImage(userId, profile.conceptImageFileId),
            updatedAt = profile.updatedAt,
        )
    }

    @GetMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/prop-profile")
    fun getPropProfile(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Response> {
        val view = assetService.loadPropProfile(projectId, episodeId, assetId) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(buildPropProfileData(userId, view)).build()
    }

    @PutMapping("/api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/prop-profile")
    fun updatePropProfile(
        @PathVariable("project_id") projectId: Long,
        @PathVariable("episode_id") episodeId: Long,
        @PathVariable("asset_id") assetId: Long,
        @AuthenticationPrincipal userId: Long?,
        @RequestBody request: PropProfileRequest,
    ): ResponseEntity<Response> {
        val command = AssetService.PropProfileCommand(
            identityTitle = request.identityTitle,
            category = request.category,
            plotFunction = request.plotFunction,
            description = request.description,
            conceptImageFileId = request.conceptImageFileId,
        )
        val view = assetService.savePropProfile(projectId, episodeId, assetId, command) ?: return responseBuilder.notFound().build()
        return responseBuilder.ok().data(buildPropProfileData(userId, view)).build()
    }

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
        return responseBuilder.badRequest().message("剧集资产提取已切换为异步剧本解析任务，请先提交任务并在结果确认后采纳").build()
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
