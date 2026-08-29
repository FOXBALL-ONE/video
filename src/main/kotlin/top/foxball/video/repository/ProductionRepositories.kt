package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.MediaFile
import top.foxball.video.entity.jdbc.MediaType
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskDependency
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.QualityCheckItem
import top.foxball.video.entity.jdbc.QualityCheckRun
import top.foxball.video.entity.jdbc.VideoRenderSettings

/** 项目媒体文件的数据访问接口。 */
interface MediaFileRepository : JpaRepository<MediaFile, Long> {
    /** 查询项目的全部媒体文件，最新文件优先。 */
    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: Long): List<MediaFile>

    /** 按媒体类型查询项目文件。 */
    fun findAllByProjectIdAndMediaTypeOrderByCreatedAtDesc(projectId: Long, mediaType: MediaType): List<MediaFile>

    /** 按存储键查询媒体文件。 */
    fun findByStorageKey(storageKey: String): MediaFile?
}

/** AI 生成和视频合成任务的数据访问接口。 */
interface ProductionTaskRepository : JpaRepository<ProductionTask, Long> {
    /** 查询项目的全部任务，最新任务优先。 */
    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: Long): List<ProductionTask>

    /** 按项目和任务状态查询任务。 */
    fun findAllByProjectIdAndStatusOrderByCreatedAtAsc(projectId: Long, status: ProductionTaskStatus): List<ProductionTask>

    /** 查询项目版本的全部任务。 */
    fun findAllByProjectVersionIdOrderByCreatedAtDesc(projectVersionId: Long): List<ProductionTask>
}

/** 制作任务依赖关系的数据访问接口。 */
interface ProductionTaskDependencyRepository : JpaRepository<ProductionTaskDependency, Long> {
    /** 查询任务依赖的全部前置任务。 */
    fun findAllByTaskId(taskId: Long): List<ProductionTaskDependency>

    /** 查询被指定任务依赖的后续任务。 */
    fun findAllByDependsOnTaskId(dependsOnTaskId: Long): List<ProductionTaskDependency>

    /** 判断任务依赖关系是否已存在。 */
    fun existsByTaskIdAndDependsOnTaskId(taskId: Long, dependsOnTaskId: Long): Boolean
}

/** 成片输出参数的数据访问接口。 */
interface VideoRenderSettingsRepository : JpaRepository<VideoRenderSettings, Long> {
    /** 查询项目最近使用的成片参数。 */
    fun findFirstByProjectIdOrderByUpdatedAtDesc(projectId: Long): VideoRenderSettings?

    /** 查询项目版本最近保存的成片参数。 */
    fun findFirstByProjectVersionIdOrderByUpdatedAtDesc(projectVersionId: Long): VideoRenderSettings?
}

/** 质量检查运行记录的数据访问接口。 */
interface QualityCheckRunRepository : JpaRepository<QualityCheckRun, Long> {
    /** 查询项目的质检运行记录，最新运行优先。 */
    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: Long): List<QualityCheckRun>

    /** 查询项目版本的质检运行记录。 */
    fun findAllByProjectVersionIdOrderByCreatedAtDesc(projectVersionId: Long): List<QualityCheckRun>
}

/** 质量检查指标的数据访问接口。 */
interface QualityCheckItemRepository : JpaRepository<QualityCheckItem, Long> {
    /** 查询质检运行中的全部指标。 */
    fun findAllByRunId(runId: Long): List<QualityCheckItem>

    /** 查询质检运行未通过的指标。 */
    fun findAllByRunIdAndPassedFalse(runId: Long): List<QualityCheckItem>
}
