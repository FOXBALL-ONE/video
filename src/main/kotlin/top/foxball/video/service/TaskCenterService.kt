package top.foxball.video.service

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.ProjectMemberRole
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.entity.jdbc.TaskEvent
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.ProjectMemberRepository
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.ProductionTaskRepository
import top.foxball.video.repository.TaskEventRepository
import java.time.LocalDateTime
import java.util.UUID

/** 统一制作任务的查询、时间线记录和取消服务。 */
@Service
class TaskCenterService(
    private val projectRepository: ProjectRepository,
    private val episodeRepository: EpisodeRepository,
    private val memberRepository: ProjectMemberRepository,
    private val taskRepository: ProductionTaskRepository,
    private val eventRepository: TaskEventRepository,
    private val cancellationRegistry: TaskCancellationRegistry,
) {
    data class Filter(
        val episodeId: Long? = null,
        val statuses: Set<ProductionTaskStatus> = emptySet(),
        val taskTypes: Set<ProductionTaskType> = emptySet(),
        val createdFrom: LocalDateTime? = null,
        val createdTo: LocalDateTime? = null,
        val page: Int = 0,
        val pageSize: Int = 20,
        val projectId: Long? = null,
    )

    data class PageSnapshot(
        val tasks: List<ProductionTask>,
        val total: Long,
        val page: Int,
        val pageSize: Int,
        val counts: Map<String, Int>,
    )

    data class DetailSnapshot(
        val task: ProductionTask,
        val events: List<TaskEvent>,
        val dependencies: List<ProductionTask>,
    )

    @Transactional(readOnly = true)
    fun list(projectId: Long, userId: Long?, filter: Filter): PageSnapshot? {
        if (!canReadProject(projectId, userId)) return null
        if (filter.episodeId != null && episodeRepository.findByIdAndProjectId(filter.episodeId, projectId) == null) return null
        val all = taskRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
            .asSequence()
            .filter { filter.episodeId == null || it.episodeId == filter.episodeId }
            .filter { filter.statuses.isEmpty() || it.status in filter.statuses }
            .filter { filter.taskTypes.isEmpty() || it.taskType in filter.taskTypes }
            .filter { filter.createdFrom == null || (it.createdAt ?: LocalDateTime.MIN) >= filter.createdFrom }
            .filter { filter.createdTo == null || (it.createdAt ?: LocalDateTime.MIN) <= filter.createdTo }
            .toList()
        val counts = ProductionTaskStatus.entries.associate { status -> status.name to all.count { it.status == status } }
        val safePage = filter.page.coerceAtLeast(0)
        val safePageSize = filter.pageSize.coerceIn(1, 100)
        val from = (safePage * safePageSize).coerceAtMost(all.size)
        val to = (from + safePageSize).coerceAtMost(all.size)
        return PageSnapshot(all.subList(from, to), all.size.toLong(), safePage, safePageSize, counts)
    }

    /** 查询当前用户创建的全部任务；不依赖当前项目上下文。 */
    @Transactional(readOnly = true)
    fun listMine(userId: Long?, filter: Filter): PageSnapshot? {
        if (userId == null) return null
        val all = taskRepository.findAllByCreatedByOrderByCreatedAtDesc(userId)
            .asSequence()
            .filter { filter.projectId == null || it.projectId == filter.projectId }
            .filter { filter.episodeId == null || it.episodeId == filter.episodeId }
            .filter { filter.statuses.isEmpty() || it.status in filter.statuses }
            .filter { filter.taskTypes.isEmpty() || it.taskType in filter.taskTypes }
            .filter { filter.createdFrom == null || (it.createdAt ?: LocalDateTime.MIN) >= filter.createdFrom }
            .filter { filter.createdTo == null || (it.createdAt ?: LocalDateTime.MIN) <= filter.createdTo }
            .toList()
        val counts = ProductionTaskStatus.entries.associate { status -> status.name to all.count { it.status == status } }
        val safePage = filter.page.coerceAtLeast(0)
        val safePageSize = filter.pageSize.coerceIn(1, 100)
        val from = (safePage * safePageSize).coerceAtMost(all.size)
        val to = (from + safePageSize).coerceAtMost(all.size)
        return PageSnapshot(all.subList(from, to), all.size.toLong(), safePage, safePageSize, counts)
    }

    @Transactional(readOnly = true)
    fun detail(projectId: Long, taskId: Long, userId: Long?): DetailSnapshot? {
        if (!canReadProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        return DetailSnapshot(
            task = task,
            events = eventRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId),
            dependencies = emptyList(),
        )
    }

    /**
     * 请求取消一个任务。终态任务直接返回当前快照，保证重复请求幂等。
     * 运行中任务先进入 CANCEL_REQUESTED，再由 worker 确认 CANCELLED。
     */
    @Transactional
    fun cancel(projectId: Long, taskId: Long, userId: Long?, reason: String?): ProductionTask? {
        if (!canWriteProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        if (userId != null && !hasAdminAuthority() && task.createdBy != userId) {
            val role = memberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId)
                .firstOrNull { it.userId == userId }?.role
            if (role != ProjectMemberRole.OWNER) return null
        }
        val current = task.status
        if (current == ProductionTaskStatus.SUCCEEDED || current == ProductionTaskStatus.FAILED || current == ProductionTaskStatus.CANCELLED || current == ProductionTaskStatus.CANCEL_REQUESTED) {
            return task
        }
        val now = LocalDateTime.now()
        task.cancelRequestedAt = now
        task.cancelledBy = userId
        task.cancelReason = reason?.trim()?.takeIf { it.isNotEmpty() }?.take(200)
        task.correlationId = task.correlationId ?: UUID.randomUUID().toString()
        if (current == ProductionTaskStatus.RUNNING) {
            task.status = ProductionTaskStatus.CANCEL_REQUESTED
            cancellationRegistry.request(requireNotNull(task.id))
            saveEvent(task, "CANCEL_REQUESTED", current, task.status, userId, "TASK_CANCEL_REQUESTED")
        } else {
            task.status = ProductionTaskStatus.CANCELLED
            task.errorCode = "TASK_CANCELLED"
            task.errorMessage = "任务已取消"
            task.stage = "CANCELLED"
            task.completedAt = now
            cancellationRegistry.clear(requireNotNull(task.id))
            saveEvent(task, "CANCELLED", current, task.status, userId, "TASK_CANCELLED")
        }
        return taskRepository.save(task)
    }

    @Transactional
    fun recordEnqueued(tasks: Collection<ProductionTask>) {
        tasks.forEach { task ->
            val taskId = task.id ?: return@forEach
            if (eventRepository.findAllByTaskIdOrderByCreatedAtAsc(taskId).isNotEmpty()) return@forEach
            task.correlationId = task.correlationId ?: UUID.randomUUID().toString()
            task.title = task.title ?: defaultTitle(task.taskType)
            task.queuedAt = task.queuedAt ?: LocalDateTime.now()
            task.stage = task.stage ?: "QUEUED"
            taskRepository.save(task)
            saveEvent(task, "CREATED", null, ProductionTaskStatus.PENDING, task.createdBy, "TASK_CREATED")
            saveEvent(task, "ENQUEUED", ProductionTaskStatus.PENDING, task.status, null, "TASK_ENQUEUED")
        }
    }

    @Transactional
    fun markCancelledByWorker(taskId: Long): ProductionTask? {
        val task = taskRepository.findWithLockById(taskId) ?: return null
        if (task.status != ProductionTaskStatus.CANCEL_REQUESTED && task.status != ProductionTaskStatus.RUNNING) return task
        val previous = task.status
        task.status = ProductionTaskStatus.CANCELLED
        task.errorCode = "TASK_CANCELLED"
        task.errorMessage = "任务已取消"
        task.stage = "CANCELLED"
        task.completedAt = LocalDateTime.now()
        cancellationRegistry.clear(taskId)
        saveEvent(task, "CANCELLED", previous, task.status, task.cancelledBy, "TASK_CANCELLED")
        return taskRepository.save(task)
    }

    @Transactional
    fun markRunning(taskId: Long): ProductionTask? {
        val task = taskRepository.findWithLockById(taskId) ?: return null
        if (task.status == ProductionTaskStatus.CANCEL_REQUESTED) return markCancelledByWorker(taskId)
        if (task.status == ProductionTaskStatus.RUNNING) return null
        if (task.status != ProductionTaskStatus.PENDING && task.status != ProductionTaskStatus.QUEUED) return task
        val previous = task.status
        task.status = ProductionTaskStatus.RUNNING
        task.attempt = (task.attempt + 1).coerceAtLeast(1)
        task.startedAt = task.startedAt ?: LocalDateTime.now()
        task.stage = "RUNNING"
        saveEvent(task, "STARTED", previous, task.status, null, "TASK_STARTED")
        return taskRepository.save(task)
    }

    @Transactional
    fun markSucceeded(taskId: Long, stagedStage: String? = null): ProductionTask? {
        val task = taskRepository.findWithLockById(taskId) ?: return null
        if (task.status == ProductionTaskStatus.CANCEL_REQUESTED) {
            return markCancelledByWorker(taskId)
        }
        if (task.status != ProductionTaskStatus.RUNNING) return task
        val previous = task.status
        task.status = ProductionTaskStatus.SUCCEEDED
        task.progress = 100
        task.stage = stagedStage?.take(80) ?: "COMPLETED"
        task.completedAt = LocalDateTime.now()
        cancellationRegistry.clear(taskId)
        saveEvent(task, "SUCCEEDED", previous, task.status, null, "TASK_SUCCEEDED")
        return taskRepository.save(task)
    }

    @Transactional
    fun markFailed(taskId: Long, errorCode: String?, errorMessage: String?): ProductionTask? {
        val task = taskRepository.findWithLockById(taskId) ?: return null
        if (task.status == ProductionTaskStatus.CANCEL_REQUESTED) return markCancelledByWorker(taskId)
        if (task.status != ProductionTaskStatus.RUNNING && task.status != ProductionTaskStatus.QUEUED) return task
        val previous = task.status
        task.status = ProductionTaskStatus.FAILED
        task.errorCode = errorCode?.take(100)
        task.errorMessage = errorMessage?.take(2000)
        task.stage = "FAILED"
        task.completedAt = LocalDateTime.now()
        cancellationRegistry.clear(taskId)
        saveEvent(task, "FAILED", previous, task.status, null, errorCode?.take(100) ?: "TASK_FAILED")
        return taskRepository.save(task)
    }

    @Transactional(readOnly = true)
    fun isCancellationRequested(taskId: Long): Boolean =
        cancellationRegistry.isRequested(taskId) || taskRepository.findById(taskId).orElse(null)?.status == ProductionTaskStatus.CANCEL_REQUESTED

    @Transactional
    fun updateProgress(taskId: Long, progress: Int, stage: String?, indeterminate: Boolean = false): ProductionTask? {
        val task = taskRepository.findWithLockById(taskId) ?: return null
        if (task.status != ProductionTaskStatus.RUNNING) return task
        task.progress = progress.coerceIn(task.progress, 100)
        task.stage = stage?.take(80)
        task.progressIndeterminate = indeterminate
        saveEvent(task, "PROGRESS", task.status, task.status, null, stage?.take(100))
        return taskRepository.save(task)
    }

    private fun saveEvent(
        task: ProductionTask,
        eventType: String,
        from: ProductionTaskStatus?,
        to: ProductionTaskStatus?,
        actorId: Long?,
        messageCode: String?,
    ) {
        eventRepository.save(
            TaskEvent(
                taskId = requireNotNull(task.id),
                eventType = eventType,
                fromStatus = from,
                toStatus = to,
                stage = task.stage,
                progress = task.progress,
                messageCode = messageCode,
                actorId = actorId,
                correlationId = task.correlationId,
            ),
        )
    }

    private fun defaultTitle(type: ProductionTaskType): String = when (type) {
        ProductionTaskType.SCRIPT_ANALYSIS -> "剧本解析"
        ProductionTaskType.CHARACTER_ENRICHMENT -> "角色内容丰富"
        ProductionTaskType.ASSET_EXTRACTION -> "资产提取"
        ProductionTaskType.IMAGE_GENERATION -> "画面生成"
        ProductionTaskType.IMAGE_EDIT -> "图片编辑"
        ProductionTaskType.VOICEOVER -> "角色配音"
        ProductionTaskType.MUSIC -> "音乐生成"
        ProductionTaskType.SOUND_EFFECT -> "音效生成"
        ProductionTaskType.SUBTITLE -> "字幕生成"
        ProductionTaskType.COMPOSITION -> "视频合成"
    }

    /** 项目级读取权限，供领域任务入口复用。 */
    fun canReadProject(projectId: Long, userId: Long?): Boolean {
        if (!projectRepository.existsById(projectId)) return false
        if (userId == null) return false
        if (hasAdminAuthority()) return true
        val members = memberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId)
        if (members.isEmpty()) return true
        return members.any { it.userId == userId }
    }

    /** 项目级编辑权限，供会改变创作数据的领域任务入口复用。 */
    fun canWriteProject(projectId: Long, userId: Long?): Boolean {
        if (!canReadProject(projectId, userId)) return false
        if (hasAdminAuthority()) return true
        val members = memberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId)
        if (members.isEmpty()) return true
        return members.any { it.userId == userId && (it.role == ProjectMemberRole.OWNER || it.role == ProjectMemberRole.EDITOR) }
    }

    private fun hasAdminAuthority(): Boolean = SecurityContextHolder.getContext().authentication?.authorities
        ?.any { it.authority == "ROLE_ADMIN" || it.authority == "task:cancel" } == true

    /** 判断当前用户是否可以取消该任务，供不同任务列表保持一致的操作语义。 */
    fun canCancel(task: ProductionTask, userId: Long?): Boolean {
        if (userId == null || task.status !in setOf(ProductionTaskStatus.PENDING, ProductionTaskStatus.QUEUED, ProductionTaskStatus.RUNNING)) return false
        if (hasAdminAuthority() || task.createdBy == userId) return true
        return memberRepository.findAllByProjectIdOrderByJoinedAtAsc(task.projectId)
            .any { it.userId == userId && it.role == ProjectMemberRole.OWNER }
    }
}
