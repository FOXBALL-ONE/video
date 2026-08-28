package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.ProductionNodeStatus
import top.foxball.video.entity.jdbc.ProductionStage
import top.foxball.video.entity.jdbc.Project
import top.foxball.video.entity.jdbc.ProjectActivity
import top.foxball.video.entity.jdbc.ProjectLifecycleAction
import top.foxball.video.entity.jdbc.ProjectStatus
import top.foxball.video.repository.ProjectRepository
import java.math.BigDecimal
import java.time.LocalDateTime

/** 项目业务服务，负责资料维护、节点推进和生命周期状态校验。 */
@Service
class ProjectService(
    private val repository: ProjectRepository,
) {
    /** 创建项目并在数据库生成主键后派生展示编号。 */
    @Transactional
    fun create(name: String, type: String, description: String, editor: String?): Project {
        val project = repository.save(
            Project(
                name = name,
                type = type,
                description = description,
                editor = editor,
                pendingDetail = "从剧本输入开始建立制作流程。",
                activities = mutableListOf(ProjectActivity("创建项目，等待开始制作", ProductionStage.SCRIPT)),
            ),
        )
        project.code = requireNotNull(project.id).toString().padStart(2, '0')
        return repository.save(project)
    }

    /** 批量创建项目；每个项目都会追加初始活动记录。 */
    @Transactional
    fun createAll(projects: List<Project>): List<Project> {
        val saved = repository.saveAll(projects)
        saved.forEach {
            it.code = requireNotNull(it.id).toString().padStart(2, '0')
            it.activities.add(ProjectActivity("创建项目，等待开始制作", ProductionStage.SCRIPT))
        }
        return repository.saveAll(saved)
    }

    /** 查询单个未删除项目及其活动时间线。 */
    @Transactional(readOnly = true)
    fun findById(id: Long): Project? = repository.findWithActivitiesById(id)?.takeIf { it.status != ProjectStatus.DELETED }

    /** 查询所有未删除项目。 */
    @Transactional(readOnly = true)
    fun findAll(): List<Project> = repository.findAllByStatusNotOrderByUpdatedAtDesc(ProjectStatus.DELETED)

    /** 按主键批量查询未删除项目。 */
    @Transactional(readOnly = true)
    fun findAllByIds(ids: Collection<Long>): List<Project> =
        repository.findAllByIdInAndStatusNot(ids.distinct(), ProjectStatus.DELETED)

    /** 更新项目基础资料；归档项目必须先恢复才能编辑。 */
    @Transactional
    fun update(
        id: Long,
        name: String,
        type: String,
        description: String,
        versionName: String,
        durationSeconds: Int,
        qualityScore: BigDecimal?,
        editor: String?,
        pendingCount: Int,
        pendingDetail: String?,
    ): Project? {
        val project = findById(id) ?: return null
        check(project.status != ProjectStatus.ARCHIVED) { "已归档项目需恢复后才能修改" }
        project.name = name
        project.type = type
        project.description = description
        project.versionName = versionName
        project.durationSeconds = durationSeconds
        project.qualityScore = qualityScore
        project.editor = editor
        project.pendingCount = pendingCount
        project.pendingDetail = pendingDetail
        project.activities.add(ProjectActivity("更新项目资料", project.currentStage))
        return repository.save(project)
    }

    /** 批量更新项目资料，参数列表由控制器负责校验长度一致。 */
    @Transactional
    fun updateAll(
        ids: List<Long>,
        names: List<String>,
        types: List<String>,
        descriptions: List<String>,
        versions: List<String>,
        durations: List<Int>,
        qualityScores: List<BigDecimal?>,
        editors: List<String?>,
        pendingCounts: List<Int>,
        pendingDetails: List<String?>,
    ): List<Project> {
        return ids.indices.mapNotNull {
            update(
                id = ids[it],
                name = names[it],
                type = types[it],
                description = descriptions[it],
                versionName = versions[it],
                durationSeconds = durations[it],
                qualityScore = qualityScores[it],
                editor = editors[it],
                pendingCount = pendingCounts[it],
                pendingDetail = pendingDetails[it],
            )
        }
    }

    /** 更新指定制作节点并重新计算整体进度。 */
    @Transactional
    fun updateStage(id: Long, stage: ProductionStage, status: ProductionNodeStatus): Project? {
        val project = findById(id) ?: return null
        check(project.status == ProjectStatus.IN_PRODUCTION) { "只有制作中的项目可以更新制作节点" }
        when (stage) {
            ProductionStage.SCRIPT -> project.scriptStatus = status
            ProductionStage.ASSETS -> project.assetStatus = status
            ProductionStage.SHOTS -> project.shotStatus = status
            ProductionStage.VIDEO -> project.videoStatus = status
        }
        project.currentStage = stage
        project.progress = listOf(project.scriptStatus, project.assetStatus, project.shotStatus, project.videoStatus)
            .sumOf {
                when (it) {
                    ProductionNodeStatus.NOT_STARTED -> 0
                    ProductionNodeStatus.IN_PROGRESS -> 12
                    ProductionNodeStatus.NEEDS_ATTENTION -> 18
                    ProductionNodeStatus.COMPLETED -> 25
                }
            }
            .coerceIn(0, 100)
        project.activities.add(ProjectActivity("${stage.name} 节点更新为 ${status.name}", stage))
        return repository.save(project)
    }

    /** 执行单个生命周期动作，并校验当前状态是否允许该动作。 */
    @Transactional
    fun transition(id: Long, action: ProjectLifecycleAction): Project? {
        val project = repository.findWithActivitiesById(id) ?: return null
        val now = LocalDateTime.now()
        when (action) {
            ProjectLifecycleAction.START -> {
                check(project.status == ProjectStatus.DRAFT) { "只有草稿项目可以开始制作" }
                project.status = ProjectStatus.IN_PRODUCTION
                project.scriptStatus = ProductionNodeStatus.IN_PROGRESS
                project.startedAt = now
            }
            ProjectLifecycleAction.ADVANCE_STAGE -> {
                check(project.status == ProjectStatus.IN_PRODUCTION) { "只有制作中的项目可以推进节点" }
                val stages = ProductionStage.entries
                val index = stages.indexOf(project.currentStage)
                check(index < stages.lastIndex) { "视频节点已是最后一个制作节点" }
                when (project.currentStage) {
                    ProductionStage.SCRIPT -> project.scriptStatus = ProductionNodeStatus.COMPLETED
                    ProductionStage.ASSETS -> project.assetStatus = ProductionNodeStatus.COMPLETED
                    ProductionStage.SHOTS -> project.shotStatus = ProductionNodeStatus.COMPLETED
                    ProductionStage.VIDEO -> project.videoStatus = ProductionNodeStatus.COMPLETED
                }
                project.currentStage = stages[index + 1]
                when (project.currentStage) {
                    ProductionStage.SCRIPT -> project.scriptStatus = ProductionNodeStatus.IN_PROGRESS
                    ProductionStage.ASSETS -> project.assetStatus = ProductionNodeStatus.IN_PROGRESS
                    ProductionStage.SHOTS -> project.shotStatus = ProductionNodeStatus.IN_PROGRESS
                    ProductionStage.VIDEO -> project.videoStatus = ProductionNodeStatus.IN_PROGRESS
                }
                project.progress = (index + 1) * 25
            }
            ProjectLifecycleAction.COMPLETE -> {
                check(project.status == ProjectStatus.IN_PRODUCTION) { "只有制作中的项目可以完成" }
                check(project.currentStage == ProductionStage.VIDEO) { "完成项目之前必须推进到视频制作节点" }
                project.videoStatus = ProductionNodeStatus.COMPLETED
                project.status = ProjectStatus.COMPLETED
                project.progress = 100
                project.completedAt = now
            }
            ProjectLifecycleAction.ARCHIVE -> {
                check(project.status == ProjectStatus.COMPLETED) { "只有已完成项目可以归档" }
                project.previousStatus = project.status
                project.status = ProjectStatus.ARCHIVED
                project.archivedAt = now
            }
            ProjectLifecycleAction.RESTORE -> {
                check(project.status == ProjectStatus.ARCHIVED || project.status == ProjectStatus.DELETED) { "只有已归档或已删除项目可以恢复" }
                project.status = project.previousStatus ?: ProjectStatus.DRAFT
                project.previousStatus = null
                project.archivedAt = null
                project.deletedAt = null
            }
            ProjectLifecycleAction.REOPEN -> {
                check(project.status == ProjectStatus.COMPLETED) { "只有已完成项目可以重新打开" }
                project.status = ProjectStatus.IN_PRODUCTION
                project.videoStatus = ProductionNodeStatus.NEEDS_ATTENTION
                project.completedAt = null
                project.progress = 90
            }
            ProjectLifecycleAction.DELETE -> {
                check(project.status != ProjectStatus.DELETED) { "项目已经删除" }
                project.previousStatus = project.status
                project.status = ProjectStatus.DELETED
                project.deletedAt = now
            }
        }
        project.activities.add(ProjectActivity("项目生命周期操作：${action.name}", project.currentStage, now))
        return repository.save(project)
    }

    /** 对多个项目依次执行同一个生命周期动作。 */
    @Transactional
    fun transitionAll(ids: Collection<Long>, action: ProjectLifecycleAction): List<Project> =
        ids.distinct().mapNotNull { transition(it, action) }
}
