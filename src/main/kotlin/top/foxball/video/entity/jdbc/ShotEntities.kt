package top.foxball.video.entity.jdbc

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

/** 可执行的视频分镜。 */
@Entity
@Table(name = "shots")
class Shot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "project_version_id")
    /** 关联的项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Column(name = "script_scene_id")
    /** 对应剧本场次主键。 */
    var scriptSceneId: Long? = null,

    @Column(name = "sequence_no", nullable = false)
    /** 镜头在项目中的顺序号。 */
    var sequenceNo: Int = 1,

    @Column(nullable = false, length = 200)
    /** 镜头标题。 */
    var title: String = "",

    @Column(length = 200)
    /** 拍摄地点或场景名称。 */
    var location: String? = null,

    @Column(name = "time_of_day", length = 50)
    /** 镜头发生时段。 */
    var timeOfDay: String? = null,

    @Column(name = "interior_exterior", length = 20)
    /** 内景或外景标识。 */
    var interiorExterior: String? = null,

    @Column(name = "shot_size", length = 50)
    /** 景别，例如远景、中景或特写。 */
    var shotSize: String? = null,

    @Column(name = "camera_movement", columnDefinition = "text")
    /** 运镜方式和镜头调度说明。 */
    var cameraMovement: String? = null,

    @Column(columnDefinition = "text")
    /** 镜头中人物或物体的动作。 */
    var action: String? = null,

    @Column(columnDefinition = "text")
    /** 镜头对应对白或旁白。 */
    var dialogue: String? = null,

    @Column(columnDefinition = "text")
    /** 用于生成画面的正向提示词。 */
    var prompt: String? = null,

    @Column(name = "negative_prompt", columnDefinition = "text")
    /** 用于生成画面的反向提示词。 */
    var negativePrompt: String? = null,

    @Column(name = "duration_seconds", nullable = false)
    /** 镜头时长，单位为秒。 */
    var durationSeconds: Double = 0.0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    /** 镜头制作状态。 */
    var status: ShotStatus = ShotStatus.DRAFT,

    @Column(name = "preview_file_id")
    /** 镜头预览媒体文件主键。 */
    var previewFileId: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 镜头创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 镜头最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** 镜头引用的角色、场景和道具。 */
@Entity
@Table(name = "shot_assets")
class ShotAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "shot_id", nullable = false)
    /** 镜头主键。 */
    var shotId: Long = 0,

    @Column(name = "asset_id", nullable = false)
    /** 被引用资产主键。 */
    var assetId: Long = 0,

    @Column(name = "usage_type", length = 40)
    /** 资产在镜头中的用途。 */
    var usageType: String? = null,

    @Column(name = "sort_order", nullable = false)
    /** 同类引用的排序值。 */
    var sortOrder: Int = 0,
)

/** 相邻镜头的连续性检查问题。 */
@Entity
@Table(name = "shot_continuity_issues")
class ShotContinuityIssue(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "from_shot_id", nullable = false)
    /** 问题起始镜头主键。 */
    var fromShotId: Long = 0,

    @Column(name = "to_shot_id", nullable = false)
    /** 问题目标镜头主键。 */
    var toShotId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 40)
    /** 连续性问题类型。 */
    var issueType: ContinuityIssueType = ContinuityIssueType.AXIS,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 问题严重级别。 */
    var severity: IssueSeverity = IssueSeverity.WARNING,

    @Column(nullable = false, length = 1000)
    /** 问题描述。 */
    var description: String = "",

    @Column(length = 1000)
    /** 建议的修复方案。 */
    var suggestion: String? = null,

    @Column(nullable = false)
    /** 是否已经修复。 */
    var resolved: Boolean = false,

    @Column(name = "resolved_at")
    /** 问题修复时间。 */
    var resolvedAt: LocalDateTime? = null,
)

/** 镜头制作状态。 */
enum class ShotStatus { DRAFT, READY, NEEDS_ATTENTION, GENERATING, GENERATED, APPROVED }
/** 镜头连续性检查类型。 */
enum class ContinuityIssueType { AXIS, COSTUME, LIGHTING, SCALE, ACTION, OTHER }
