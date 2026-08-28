package top.foxball.video.entity.jdbc

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 视频制作项目聚合根。
 *
 * 项目从草稿开始，依次经过剧本、资产、分镜和视频四个制作节点，
 * 最终进入已完成或已归档状态；删除通过 [deletedAt] 和 [ProjectStatus.DELETED] 软删除。
 */
@Entity
@Table(name = "projects")
class Project(
    /** 数据库主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(unique = true, length = 20)
    /** 对外展示的项目编号，由服务层在首次保存后根据主键生成。 */
    var code: String? = null,

    /** 项目名称。 */
    @Column(nullable = false, length = 100)
    var name: String = "",

    /** 项目类型，例如悬疑短片、纪录短片或品牌宣传片。 */
    @Column(nullable = false, length = 50)
    var type: String = "",

    /** 项目简介及制作目标。 */
    @Column(nullable = false, length = 1000)
    var description: String = "",

    /** 当前项目生命周期状态。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: ProjectStatus = ProjectStatus.DRAFT,

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 20)
    /** 删除或归档前的状态，用于恢复项目时还原原状态。 */
    var previousStatus: ProjectStatus? = null,

    /** 当前正在处理的制作节点。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_stage", nullable = false, length = 20)
    var currentStage: ProductionStage = ProductionStage.SCRIPT,

    @Enumerated(EnumType.STRING)
    @Column(name = "script_status", nullable = false, length = 20)
    /** 剧本输入节点状态。 */
    var scriptStatus: ProductionNodeStatus = ProductionNodeStatus.NOT_STARTED,

    /** 资产清单节点状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "asset_status", nullable = false, length = 20)
    var assetStatus: ProductionNodeStatus = ProductionNodeStatus.NOT_STARTED,

    @Enumerated(EnumType.STRING)
    @Column(name = "shot_status", nullable = false, length = 20)
    /** 分镜管理节点状态。 */
    var shotStatus: ProductionNodeStatus = ProductionNodeStatus.NOT_STARTED,

    /** 视频制作节点状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "video_status", nullable = false, length = 20)
    var videoStatus: ProductionNodeStatus = ProductionNodeStatus.NOT_STARTED,

    @Column(nullable = false)
    /** 按四个制作节点计算的整体完成度，范围为 0 到 100。 */
    var progress: Int = 0,

    /** 当前编辑版本号，例如 v1.4。 */
    @Column(name = "version_name", nullable = false, length = 20)
    var versionName: String = "v0.1",

    @Column(name = "duration_seconds", nullable = false)
    /** 成片预计时长，单位为秒。 */
    var durationSeconds: Int = 0,

    /** 质量评分，范围为 0 到 10。 */
    @Column(name = "quality_score", precision = 4, scale = 2)
    var qualityScore: BigDecimal? = null,

    @Column(length = 100)
    /** 当前负责编辑或制作的人员。 */
    var editor: String? = null,

    /** 尚未处理的项目事项数量。 */
    @Column(name = "pending_count", nullable = false)
    var pendingCount: Int = 0,

    @Column(name = "pending_detail", length = 1000)
    /** 尚未处理事项的摘要。 */
    var pendingDetail: String? = null,

    /** 项目活动时间线，按发生顺序保存。 */
    @ElementCollection
    @CollectionTable(name = "project_activities", joinColumns = [JoinColumn(name = "project_id")])
    @OrderColumn(name = "sort_order")
    var activities: MutableList<ProjectActivity> = mutableListOf(),

    @Column(name = "started_at")
    /** 项目首次开始制作的时间。 */
    var startedAt: LocalDateTime? = null,

    /** 项目完成时间。 */
    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,

    @Column(name = "archived_at")
    /** 项目归档时间。 */
    var archivedAt: LocalDateTime? = null,

    /** 项目软删除时间。 */
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 项目创建时间。 */
    var createdAt: LocalDateTime? = null,

    /** 最近一次更新时间。 */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号，避免并发编辑覆盖彼此的修改。 */
    var entityVersion: Long = 0,
)

/** 项目活动时间线中的一条记录。 */
@Embeddable
data class ProjectActivity(
    /** 活动描述。 */
    @Column(nullable = false, length = 200)
    var title: String = "",

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    /** 产生该活动的制作节点。 */
    var stage: ProductionStage? = null,

    /** 活动发生时间。 */
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
)

/** 项目生命周期状态。 */
enum class ProjectStatus {
    DRAFT,
    IN_PRODUCTION,
    COMPLETED,
    ARCHIVED,
    DELETED,
}

/** 四个制作节点及其固定推进顺序。 */
enum class ProductionStage {
    SCRIPT,
    ASSETS,
    SHOTS,
    VIDEO,
}

/** 单个制作节点的处理状态。 */
enum class ProductionNodeStatus {
    NOT_STARTED,
    IN_PROGRESS,
    NEEDS_ATTENTION,
    COMPLETED,
}

/** 可由接口触发的项目生命周期动作。 */
enum class ProjectLifecycleAction {
    START,
    ADVANCE_STAGE,
    COMPLETE,
    ARCHIVE,
    RESTORE,
    REOPEN,
    DELETE,
}
