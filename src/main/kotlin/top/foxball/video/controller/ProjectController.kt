package top.foxball.video.controller

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.ProductionNodeStatus
import top.foxball.video.entity.jdbc.ProductionStage
import top.foxball.video.entity.jdbc.Project
import top.foxball.video.entity.jdbc.ProjectLifecycleAction
import top.foxball.video.service.ProjectService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder
import java.math.BigDecimal
import java.time.LocalDateTime

@Validated
@RestController
/** 项目管理接口，覆盖资料 CRUD、制作节点和完整生命周期。 */
class ProjectController(
    private val projectService: ProjectService,
    private val responseBuilder: ResponseBuilder,
) {
    @PostMapping("/api/projects")
    /** 创建单个制作项目。 */
    fun createProject(
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("type") @NotBlank @Size(max = 50) type: String,
        @RequestParam("description", defaultValue = "") @Size(max = 1000) description: String,
        @RequestParam("editor", required = false) @Size(max = 100) editor: String?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val code: String,
            val name: String,
            val type: String,
            val description: String,
            val status: String,
            @param:JsonProperty("current_stage") val currentStage: String,
            val progress: Int,
            val version: String,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
        )

        val project = projectService.create(name, type, description, editor)
        val rs = Response(
            requireNotNull(project.id),
            requireNotNull(project.code),
            project.name,
            project.type,
            project.description,
            project.status.name,
            project.currentStage.name,
            project.progress,
            project.versionName,
            project.createdAt,
        )
        return responseBuilder.created().data(rs).build()
    }

    @PostMapping("/api/projects/Batch")
    /** 批量创建制作项目，所有列表参数必须按下标一一对应。 */
    fun createProjects(
        @RequestParam("names") names: List<@NotBlank @Size(max = 100) String>,
        @RequestParam("types") types: List<@NotBlank @Size(max = 50) String>,
        @RequestParam("descriptions", required = false) descriptions: List<@Size(max = 1000) String>?,
        @RequestParam("editors", required = false) editors: List<@Size(max = 100) String>?,
    ): ResponseEntity<Response> {
        data class ProjectData(val id: Long, val code: String, val name: String, val type: String, val status: String)
        data class Response(val list: List<ProjectData>)

        if (names.isEmpty() || names.size != types.size || (descriptions != null && descriptions.size != names.size) || (editors != null && editors.size != names.size)) {
            return responseBuilder.badRequest().message("names、types、descriptions、editors 的数量必须一致，且至少包含一个项目").build()
        }
        val projects = names.indices.map {
            Project(
                name = names[it],
                type = types[it],
                description = descriptions?.get(it).orEmpty(),
                editor = editors?.get(it),
                pendingDetail = "从剧本输入开始建立制作流程。",
            )
        }
        val list = projectService.createAll(projects).map {
            ProjectData(requireNotNull(it.id), requireNotNull(it.code), it.name, it.type, it.status.name)
        }
        val rs = Response(list)
        return responseBuilder.created().data(rs).build()
    }

    @GetMapping("/api/projects/{id}")
    /** 查询单个项目详情及活动时间线。 */
    fun getProject(@PathVariable("id") id: Long): ResponseEntity<Response> {
        data class ActivityData(val title: String, val stage: String?, @param:JsonProperty("created_at") val createdAt: LocalDateTime)
        data class Response(
            val id: Long,
            val code: String,
            val name: String,
            val type: String,
            val description: String,
            val status: String,
            @param:JsonProperty("current_stage") val currentStage: String,
            @param:JsonProperty("script_status") val scriptStatus: String,
            @param:JsonProperty("asset_status") val assetStatus: String,
            @param:JsonProperty("shot_status") val shotStatus: String,
            @param:JsonProperty("video_status") val videoStatus: String,
            val progress: Int,
            val version: String,
            @param:JsonProperty("duration_seconds") val durationSeconds: Int,
            @param:JsonProperty("quality_score") val qualityScore: BigDecimal?,
            val editor: String?,
            @param:JsonProperty("pending_count") val pendingCount: Int,
            @param:JsonProperty("pending_detail") val pendingDetail: String?,
            val activities: List<ActivityData>,
            @param:JsonProperty("started_at") val startedAt: LocalDateTime?,
            @param:JsonProperty("completed_at") val completedAt: LocalDateTime?,
            @param:JsonProperty("archived_at") val archivedAt: LocalDateTime?,
            @param:JsonProperty("created_at") val createdAt: LocalDateTime?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        val project = projectService.findById(id) ?: return responseBuilder.notFound().build()
        val activities = project.activities.map { ActivityData(it.title, it.stage?.name, it.createdAt) }
        val rs = Response(
            requireNotNull(project.id), requireNotNull(project.code), project.name, project.type, project.description,
            project.status.name, project.currentStage.name, project.scriptStatus.name, project.assetStatus.name,
            project.shotStatus.name, project.videoStatus.name, project.progress, project.versionName,
            project.durationSeconds, project.qualityScore, project.editor, project.pendingCount, project.pendingDetail,
            activities, project.startedAt, project.completedAt, project.archivedAt, project.createdAt, project.updatedAt,
        )
        return responseBuilder.ok().data(rs).build()
    }

    @GetMapping("/api/projects")
    /** 查询项目列表；传入 ids 时只返回指定项目。 */
    fun getProjects(
        @RequestParam("ids", required = false) ids: List<Long>?,
    ): ResponseEntity<Response> {
        data class ProjectData(
            val id: Long,
            val code: String,
            val name: String,
            val type: String,
            val description: String,
            val status: String,
            @param:JsonProperty("current_stage") val currentStage: String,
            val progress: Int,
            val version: String,
            @param:JsonProperty("duration_seconds") val durationSeconds: Int,
            @param:JsonProperty("quality_score") val qualityScore: BigDecimal?,
            val editor: String?,
            @param:JsonProperty("pending_count") val pendingCount: Int,
            @param:JsonProperty("pending_detail") val pendingDetail: String?,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )
        data class Response(val list: List<ProjectData>)

        val projects = if (ids == null) projectService.findAll() else projectService.findAllByIds(ids)
        val list = projects.map {
            ProjectData(
                requireNotNull(it.id), requireNotNull(it.code), it.name, it.type, it.description, it.status.name,
                it.currentStage.name, it.progress, it.versionName, it.durationSeconds, it.qualityScore, it.editor,
                it.pendingCount, it.pendingDetail, it.updatedAt,
            )
        }
        val rs = Response(list)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{id}")
    /** 更新项目基础资料。 */
    fun updateProject(
        @PathVariable("id") id: Long,
        @RequestParam("name") @NotBlank @Size(max = 100) name: String,
        @RequestParam("type") @NotBlank @Size(max = 50) type: String,
        @RequestParam("description") @Size(max = 1000) description: String,
        @RequestParam("version") @NotBlank @Pattern(regexp = "^v\\d+\\.\\d+$") version: String,
        @RequestParam("duration_seconds") @Min(0) @Max(86400) durationSeconds: Int,
        @RequestParam("quality_score", required = false) @DecimalMin("0.00") @DecimalMax("10.00") qualityScore: BigDecimal?,
        @RequestParam("editor", required = false) @Size(max = 100) editor: String?,
        @RequestParam("pending_count") @Min(0) pendingCount: Int,
        @RequestParam("pending_detail", required = false) @Size(max = 1000) pendingDetail: String?,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val name: String,
            val type: String,
            val status: String,
            val progress: Int,
            val version: String,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        val project = try {
            projectService.update(id, name, type, description, version, durationSeconds, qualityScore, editor, pendingCount, pendingDetail)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(project.id), project.name, project.type, project.status.name, project.progress, project.versionName, project.updatedAt)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{id}/stage")
    /** 更新单个制作节点状态并重新计算项目进度。 */
    fun updateProjectStage(
        @PathVariable("id") id: Long,
        @RequestParam("stage") stage: ProductionStage,
        @RequestParam("status") status: ProductionNodeStatus,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            @param:JsonProperty("current_stage") val currentStage: String,
            @param:JsonProperty("node_status") val nodeStatus: String,
            val progress: Int,
        )

        val project = try {
            projectService.updateStage(id, stage, status)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(project.id), project.currentStage.name, status.name, project.progress)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/Batch")
    /** 批量更新项目基础资料。 */
    fun updateProjects(
        @RequestParam("ids") ids: List<Long>,
        @RequestParam("names") names: List<@NotBlank @Size(max = 100) String>,
        @RequestParam("types") types: List<@NotBlank @Size(max = 50) String>,
        @RequestParam("descriptions") descriptions: List<@Size(max = 1000) String>,
        @RequestParam("versions") versions: List<@NotBlank @Pattern(regexp = "^v\\d+\\.\\d+$") String>,
        @RequestParam("duration_seconds") durations: List<@Min(0) @Max(86400) Int>,
        @RequestParam("quality_scores", required = false) qualityScores: List<@DecimalMin("0.00") @DecimalMax("10.00") BigDecimal?>?,
        @RequestParam("editors", required = false) editors: List<@Size(max = 100) String?>?,
        @RequestParam("pending_counts") pendingCounts: List<@Min(0) Int>,
        @RequestParam("pending_details", required = false) pendingDetails: List<@Size(max = 1000) String?>?,
    ): ResponseEntity<Response> {
        data class ProjectData(val id: Long, val name: String, val status: String, val progress: Int)
        data class Response(val list: List<ProjectData>)

        val expected = ids.size
        if (expected == 0 || names.size != expected || types.size != expected || descriptions.size != expected || versions.size != expected || durations.size != expected || pendingCounts.size != expected || (qualityScores != null && qualityScores.size != expected) || (editors != null && editors.size != expected) || (pendingDetails != null && pendingDetails.size != expected)) {
            return responseBuilder.badRequest().message("批量更新的所有参数数量必须一致，且至少包含一个项目").build()
        }
        val projects = try {
            projectService.updateAll(
                ids = ids,
                names = names,
                types = types,
                descriptions = descriptions,
                versions = versions,
                durations = durations,
                qualityScores = qualityScores ?: List(expected) { null },
                editors = editors ?: List(expected) { null },
                pendingCounts = pendingCounts,
                pendingDetails = pendingDetails ?: List(expected) { null },
            )
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val list = projects.map { ProjectData(requireNotNull(it.id), it.name, it.status.name, it.progress) }
        val rs = Response(list)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/{id}/lifecycle")
    /** 执行单个项目生命周期动作。 */
    fun transitionProject(
        @PathVariable("id") id: Long,
        @RequestParam("action") action: ProjectLifecycleAction,
    ): ResponseEntity<Response> {
        data class Response(
            val id: Long,
            val status: String,
            @param:JsonProperty("current_stage") val currentStage: String,
            val progress: Int,
            @param:JsonProperty("updated_at") val updatedAt: LocalDateTime?,
        )

        val project = try {
            projectService.transition(id, action)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(project.id), project.status.name, project.currentStage.name, project.progress, project.updatedAt)
        return responseBuilder.ok().data(rs).build()
    }

    @PutMapping("/api/projects/Batch/lifecycle")
    /** 对多个项目执行同一个生命周期动作。 */
    fun transitionProjects(
        @RequestParam("ids") ids: List<Long>,
        @RequestParam("action") action: ProjectLifecycleAction,
    ): ResponseEntity<Response> {
        data class ProjectData(val id: Long, val status: String, @param:JsonProperty("current_stage") val currentStage: String, val progress: Int)
        data class Response(val list: List<ProjectData>)

        val projects = try {
            projectService.transitionAll(ids, action)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val list = projects.map { ProjectData(requireNotNull(it.id), it.status.name, it.currentStage.name, it.progress) }
        val rs = Response(list)
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/projects/{id}")
    /** 软删除单个项目。 */
    fun deleteProject(@PathVariable("id") id: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val deleted: Boolean)

        val project = try {
            projectService.transition(id, ProjectLifecycleAction.DELETE)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        } ?: return responseBuilder.notFound().build()
        val rs = Response(requireNotNull(project.id), true)
        return responseBuilder.ok().data(rs).build()
    }

    @DeleteMapping("/api/projects")
    /** 批量软删除项目。 */
    fun deleteProjects(@RequestParam("ids") ids: List<Long>): ResponseEntity<Response> {
        data class Response(val ids: List<Long>, val deleted: Int)

        val projects = try {
            projectService.transitionAll(ids, ProjectLifecycleAction.DELETE)
        } catch (exception: IllegalStateException) {
            return responseBuilder.badRequest().message(exception.message).build()
        }
        val deletedIds = projects.mapNotNull { it.id }
        val rs = Response(deletedIds, deletedIds.size)
        return responseBuilder.ok().data(rs).build()
    }
}
