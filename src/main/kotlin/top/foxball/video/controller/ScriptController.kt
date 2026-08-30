package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.service.ScriptService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

@Validated
@RestController
/** 剧本输入接口，负责当前剧本版本及结构化结果。 */
class ScriptController(
    private val scriptService: ScriptService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/projects/{project_id}/script")
    fun getScript(@PathVariable("project_id") projectId: Long): ResponseEntity<Response> {
        data class VersionData(val id: Long, @param:JsonProperty("version_no") val versionNo: Int, @param:JsonProperty("version_name") val versionName: String, val status: String, @param:JsonProperty("created_at") val createdAt: LocalDateTime?)
        data class SceneData(val id: Long, @param:JsonProperty("scene_no") val sceneNo: Int, val title: String, val location: String?, @param:JsonProperty("time_of_day") val timeOfDay: String?, @param:JsonProperty("duration_seconds") val durationSeconds: Int, val content: String?)
        data class CharacterData(val id: Long, val name: String, val description: String?, val motivation: String?, val relationship: String?)
        data class DialogueData(val id: Long, @param:JsonProperty("script_scene_id") val scriptSceneId: Long, @param:JsonProperty("character_id") val characterId: Long?, @param:JsonProperty("sequence_no") val sequenceNo: Int, val content: String, @param:JsonProperty("dialogue_type") val dialogueType: String, @param:JsonProperty("duration_seconds") val durationSeconds: Int)
        data class QualityData(val id: Long, @param:JsonProperty("rule_code") val ruleCode: String, val title: String, val note: String?, val passed: Boolean, val score: BigDecimal?)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, val title: String, val version: VersionData, val versions: List<VersionData>, val content: String, val scenes: List<SceneData>, val characters: List<CharacterData>, val dialogues: List<DialogueData>, @param:JsonProperty("quality_checks") val qualityChecks: List<QualityData>, val stats: Map<String, Any?>)

        val snapshot = scriptService.load(projectId) ?: return responseBuilder.notFound().build()
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
        )
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{project_id}/script")
    fun saveScript(
        @PathVariable("project_id") projectId: Long,
        @RequestBody @Size(max = 2_000_000) content: String,
        @RequestParam("title", required = false) title: String?,
    ): ResponseEntity<Response> {
        data class VersionData(val id: Long, @param:JsonProperty("version_no") val versionNo: Int, @param:JsonProperty("version_name") val versionName: String, val status: String, @param:JsonProperty("created_at") val createdAt: LocalDateTime?)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, val title: String, val version: VersionData, val content: String, val stats: Map<String, Any?>)

        val snapshot = scriptService.save(projectId, content, title) ?: return responseBuilder.notFound().build()
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
    ): ResponseEntity<Response> {
        data class VersionData(val id: Long, @param:JsonProperty("version_no") val versionNo: Int, @param:JsonProperty("version_name") val versionName: String, val status: String, @param:JsonProperty("locked_at") val lockedAt: LocalDateTime?)
        data class Response(@param:JsonProperty("document_id") val documentId: Long, val title: String, val version: VersionData, val content: String)

        val snapshot = scriptService.lock(projectId, userId) ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(snapshot.document.id), snapshot.document.title, VersionData(requireNotNull(snapshot.version.id), snapshot.version.versionNo, snapshot.version.versionName, snapshot.version.status.name, snapshot.version.lockedAt), snapshot.version.content)
        return responseBuilder.ok().data(rs).build()
    }
}
