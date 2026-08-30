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
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime

/** 统一管理剧本、参考图、音频、预览和成片文件。 */
@Entity
@Table(name = "media_files")
class MediaFile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；项目级素材时为空。 */
    var episodeId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false, length = 30)
    /** 媒体文件类型。 */
    var mediaType: MediaType = MediaType.IMAGE,

    @Column(name = "storage_key", nullable = false, length = 1000)
    /** 对象存储中的唯一键。 */
    var storageKey: String = "",

    @Column(length = 1000)
    /** 可访问的媒体 URL。 */
    var url: String? = null,

    @Column(name = "original_name", length = 500)
    /** 用户上传时的原始文件名。 */
    var originalName: String? = null,

    @Column(name = "mime_type", length = 200)
    /** 文件 MIME 类型。 */
    var mimeType: String? = null,

    @Column(name = "size_bytes")
    /** 文件大小，单位为字节。 */
    var sizeBytes: Long? = null,

    @Column(name = "duration_seconds")
    /** 音频或视频时长，单位为秒。 */
    var durationSeconds: Double? = null,

    /** 媒体宽度，单位为像素。 */
    var width: Int? = null,
    /** 媒体高度，单位为像素。 */
    var height: Int? = null,

    @Column(length = 128)
    /** 文件内容校验和。 */
    var checksum: String? = null,

    @Column(name = "created_by")
    /** 上传或生成文件的用户主键。 */
    var createdBy: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 文件记录创建时间。 */
    var createdAt: LocalDateTime? = null,
)

/** 视频输出画面、配音、音乐、字幕和最终合成任务。 */
@Entity
@Table(name = "production_tasks")
class ProductionTask(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；为空时兼容项目级制作任务。 */
    var episodeId: Long? = null,

    @Column(name = "project_version_id")
    /** 关联的项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 30)
    /** 任务类型。 */
    var taskType: ProductionTaskType = ProductionTaskType.IMAGE_GENERATION,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 任务执行状态。 */
    var status: ProductionTaskStatus = ProductionTaskStatus.PENDING,

    @Column(nullable = false)
    /** 任务完成百分比。 */
    var progress: Int = 0,

    @Column(name = "provider_config_id")
    /** 使用的 AI 提供商配置主键。 */
    var providerConfigId: Long? = null,

    @Column(name = "model_name", length = 200)
    /** 使用的模型名称。 */
    var modelName: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_payload", columnDefinition = "jsonb")
    /** 提交给执行器的结构化输入参数。 */
    var inputPayload: Map<String, Any?> = emptyMap(),

    @Column(name = "output_file_id")
    /** 成功后生成的输出文件主键。 */
    var outputFileId: Long? = null,

    @Column(name = "error_message", length = 2000)
    /** 失败时的错误信息。 */
    var errorMessage: String? = null,

    @Column(name = "started_at")
    /** 任务开始执行时间。 */
    var startedAt: LocalDateTime? = null,

    @Column(name = "completed_at")
    /** 任务完成或失败时间。 */
    var completedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 任务创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 任务最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** 制作任务之间的依赖边。 */
@Entity
@Table(name = "production_task_dependencies")
class ProductionTaskDependency(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "task_id", nullable = false)
    /** 当前任务主键。 */
    var taskId: Long = 0,

    @Column(name = "depends_on_task_id", nullable = false)
    /** 当前任务依赖的前置任务主键。 */
    var dependsOnTaskId: Long = 0,
)

/** 成片参数。 */
@Entity
@Table(name = "video_render_settings")
class VideoRenderSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；为空时兼容项目级成片设置。 */
    var episodeId: Long? = null,

    @Column(name = "project_version_id")
    /** 关联的项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Column(name = "aspect_ratio", nullable = false, length = 20)
    /** 输出画面宽高比。 */
    var aspectRatio: String = "9:16",

    @Column(nullable = false, length = 30)
    /** 输出分辨率，例如 1080x1920。 */
    var resolution: String = "1080x1920",

    @Column(nullable = false, length = 50)
    /** 输出视觉风格。 */
    var style: String = "cinematic",

    @Column(nullable = false)
    /** 输出帧率。 */
    var fps: Int = 24,

    @Enumerated(EnumType.STRING)
    @Column(name = "subtitle_mode", nullable = false, length = 20)
    /** 字幕生成模式。 */
    var subtitleMode: SubtitleMode = SubtitleMode.SMART,

    @Column(name = "audio_sample_rate")
    /** 音频采样率。 */
    var audioSampleRate: Int? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 参数记录创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 参数记录最近更新时间。 */
    var updatedAt: LocalDateTime? = null,
)

/** 一次完整的质量门禁运行。 */
@Entity
@Table(name = "quality_check_runs")
class QualityCheckRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；项目级质检时为空。 */
    var episodeId: Long? = null,

    @Column(name = "project_version_id")
    /** 关联的项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 执行质检的制作阶段。 */
    var stage: ProductionStage = ProductionStage.VIDEO,

    @Column(name = "overall_score", precision = 5, scale = 2)
    /** 本次质检的综合评分。 */
    var overallScore: java.math.BigDecimal? = null,

    @Column(nullable = false)
    /** 是否通过全部质量门禁。 */
    var passed: Boolean = false,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 质检运行创建时间。 */
    var createdAt: LocalDateTime? = null,
)

/** 质量门禁中的一条指标。 */
@Entity
@Table(name = "quality_check_items")
class QualityCheckItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "run_id", nullable = false)
    /** 所属质检运行主键。 */
    var runId: Long = 0,

    @Column(name = "rule_code", nullable = false, length = 50)
    /** 质量规则编码。 */
    var ruleCode: String = "",

    @Column(name = "metric_value", precision = 10, scale = 3)
    /** 实际检测指标值。 */
    var metricValue: java.math.BigDecimal? = null,

    @Column(name = "threshold_value", precision = 10, scale = 3)
    /** 规则要求的阈值。 */
    var thresholdValue: java.math.BigDecimal? = null,

    @Column(nullable = false)
    /** 指标是否通过。 */
    var passed: Boolean = false,

    @Column(length = 1000)
    /** 指标检查说明。 */
    var message: String? = null,
)

/** 项目成员与协作角色。 */
@Entity
@Table(name = "project_members")
class ProjectMember(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "user_id", nullable = false)
    /** 成员用户主键。 */
    var userId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 成员在项目中的协作角色。 */
    var role: ProjectMemberRole = ProjectMemberRole.EDITOR,

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    /** 加入项目时间。 */
    var joinedAt: LocalDateTime? = null,
)

/** AI 对话及其消息，用于需要审计或复用助手上下文的场景。 */
@Entity
@Table(name = "ai_conversations")
class AiConversation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；项目级对话时为空。 */
    var episodeId: Long? = null,

    @Column(name = "user_id")
    /** 发起会话的用户主键。 */
    var userId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 会话所属制作阶段。 */
    var stage: ProductionStage = ProductionStage.SCRIPT,

    @Column(length = 200)
    /** 会话标题。 */
    var title: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 会话创建时间。 */
    var createdAt: LocalDateTime? = null,
)

/** AI 会话中的一条用户、助手或系统消息。 */
@Entity
@Table(name = "ai_messages")
class AiMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "conversation_id", nullable = false)
    /** 所属 AI 会话主键。 */
    var conversationId: Long = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 消息发送者角色。 */
    var role: AiMessageRole = AiMessageRole.USER,

    @Column(nullable = false, columnDefinition = "text")
    /** 消息正文。 */
    var content: String = "",

    @Column(name = "model_name", length = 200)
    /** 助手生成消息所使用的模型名称。 */
    var modelName: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 消息创建时间。 */
    var createdAt: LocalDateTime? = null,
)

/** 时间线轨道和剪辑片段，支撑高级剪辑工作流。 */
@Entity
@Table(name = "timeline_tracks")
class TimelineTrack(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；项目级时间线时为空。 */
    var episodeId: Long? = null,

    @Column(nullable = false, length = 100)
    /** 轨道名称。 */
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 轨道媒体类型。 */
    var type: TimelineTrackType = TimelineTrackType.VIDEO,

    @Column(name = "sort_order", nullable = false)
    /** 轨道在时间线中的排序。 */
    var sortOrder: Int = 0,
)

/** 时间线上的一个媒体剪辑片段。 */
@Entity
@Table(name = "timeline_clips")
class TimelineClip(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "track_id", nullable = false)
    /** 所属时间线轨道主键。 */
    var trackId: Long = 0,

    @Column(name = "media_file_id")
    /** 片段引用的媒体文件主键。 */
    var mediaFileId: Long? = null,

    @Column(name = "start_seconds", nullable = false)
    /** 片段在时间线上的起始时间，单位为秒。 */
    var startSeconds: Double = 0.0,

    @Column(name = "duration_seconds", nullable = false)
    /** 片段在时间线上的持续时长，单位为秒。 */
    var durationSeconds: Double = 0.0,

    @Column(name = "in_point_seconds", nullable = false)
    /** 源媒体入点，单位为秒。 */
    var inPointSeconds: Double = 0.0,

    @Column(name = "out_point_seconds", nullable = false)
    /** 源媒体出点，单位为秒。 */
    var outPointSeconds: Double = 0.0,

    @Column(name = "sort_order", nullable = false)
    /** 同一轨道内的片段排序。 */
    var sortOrder: Int = 0,
)

/** 媒体文件类型。 */
enum class MediaType { SCRIPT_SOURCE, IMAGE, AUDIO, VIDEO, SUBTITLE }
/** 生产任务类型。 */
enum class ProductionTaskType { IMAGE_GENERATION, VOICEOVER, MUSIC, SOUND_EFFECT, SUBTITLE, COMPOSITION }
/** 生产任务执行状态。 */
enum class ProductionTaskStatus { PENDING, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }
/** 字幕生成模式。 */
enum class SubtitleMode { SMART, OFF }
/** 项目成员角色。 */
enum class ProjectMemberRole { OWNER, EDITOR, REVIEWER, VIEWER }
/** AI 消息发送者角色。 */
enum class AiMessageRole { USER, ASSISTANT, SYSTEM }
/** 时间线轨道类型。 */
enum class TimelineTrackType { VIDEO, AUDIO, SUBTITLE, EFFECT }
