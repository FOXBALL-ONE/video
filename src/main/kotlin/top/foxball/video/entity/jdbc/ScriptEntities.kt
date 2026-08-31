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

/** 项目剧本文档；具体内容保存在可版本化的 ScriptVersion 中。 */
@Entity
@Table(name = "script_documents")
class ScriptDocument(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。项目下可以包含多份剧集剧本文档。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；为空时兼容早期项目级剧本文档。 */
    var episodeId: Long? = null,

    @Column(nullable = false, length = 200)
    /** 剧本文档标题。 */
    var title: String = "",

    @Column(name = "current_version_id")
    /** 当前生效的剧本版本主键。 */
    var currentVersionId: Long? = null,

    @Column(name = "created_by")
    /** 创建文档的用户主键。 */
    var createdBy: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 文档创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 文档最近更新时间。 */
    var updatedAt: LocalDateTime? = null,
)

/** 剧本文本及其 AI 结构化结果。 */
@Entity
@Table(name = "script_versions")
class ScriptVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "script_document_id", nullable = false)
    /** 所属剧本文档主键。 */
    var scriptDocumentId: Long = 0,

    @Column(name = "project_version_id")
    /** 关联的项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Column(name = "version_no", nullable = false)
    /** 文档内递增的版本序号。 */
    var versionNo: Int = 1,

    @Column(name = "version_name", nullable = false, length = 20)
    /** 对外展示的版本名称。 */
    var versionName: String = "v0.1",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 剧本版本状态。 */
    var status: VersionStatus = VersionStatus.DRAFT,

    @Column(nullable = false, columnDefinition = "text")
    /** 剧本文本正文。 */
    var content: String = "",

    @Column(name = "source_file_id")
    /** 导入时使用的源文件主键。 */
    var sourceFileId: Long? = null,

    @Column(name = "scene_count", nullable = false)
    /** 解析出的场次数量。 */
    var sceneCount: Int = 0,

    @Column(name = "character_count", nullable = false)
    /** 解析出的角色数量。 */
    var characterCount: Int = 0,

    @Column(name = "dialogue_count", nullable = false)
    /** 解析出的对白数量。 */
    var dialogueCount: Int = 0,

    @Column(name = "estimated_duration_seconds", nullable = false)
    /** 根据剧本估算的成片时长，单位为秒。 */
    var estimatedDurationSeconds: Int = 0,

    @Column(name = "structure_score", precision = 4, scale = 2)
    /** AI 或人工评估的结构评分。 */
    var structureScore: java.math.BigDecimal? = null,

    @Column(name = "created_by")
    /** 创建该版本的用户主键。 */
    var createdBy: Long? = null,

    @Column(name = "locked_by")
    /** 锁定该版本的用户主键。 */
    var lockedBy: Long? = null,

    @Column(name = "locked_at")
    /** 版本锁定时间。 */
    var lockedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 版本创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 版本最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Column(name = "ai_analysis_json", columnDefinition = "text")
    /** 大模型剧本拆解结果，保留原始 JSON 以便前端完整展示和后续重放。 */
    var aiAnalysisJson: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_analysis_status", nullable = false, length = 20)
    /** 大模型解析状态。 */
    var aiAnalysisStatus: ScriptAnalysisStatus = ScriptAnalysisStatus.NOT_PARSED,

    @Column(name = "ai_analysis_provider_config_id")
    /** 执行解析时使用的提供商配置。 */
    var aiAnalysisProviderConfigId: Long? = null,

    @Column(name = "ai_analysis_model", length = 200)
    /** 执行解析时使用的模型 ID。 */
    var aiAnalysisModel: String? = null,

    @Column(name = "ai_analysis_at")
    /** 最近一次解析完成时间。 */
    var aiAnalysisAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** 剧本中的场次。 */
@Entity
@Table(name = "script_scenes")
class ScriptScene(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "script_version_id", nullable = false)
    /** 所属剧本版本主键。 */
    var scriptVersionId: Long = 0,

    @Column(name = "scene_no", nullable = false)
    /** 剧本中的场次编号。 */
    var sceneNo: Int = 1,

    @Column(nullable = false, length = 200)
    /** 场次标题。 */
    var title: String = "",

    @Column(length = 200)
    /** 场景地点。 */
    var location: String? = null,

    @Column(name = "time_of_day", length = 50)
    /** 场景发生时段。 */
    var timeOfDay: String? = null,

    @Column(name = "interior_exterior", length = 20)
    /** 内景或外景标识。 */
    var interiorExterior: String? = null,

    @Column(columnDefinition = "text")
    /** 场次正文或舞台动作描述。 */
    var content: String? = null,

    @Column(name = "duration_seconds", nullable = false)
    /** 场次预计时长，单位为秒。 */
    var durationSeconds: Int = 0,

    @Column(name = "sort_order", nullable = false)
    /** 场次在剧本中的排序值。 */
    var sortOrder: Int = 0,
)

/** 剧本角色及其人物动机。 */
@Entity
@Table(name = "script_characters")
class ScriptCharacter(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "script_version_id", nullable = false)
    /** 所属剧本版本主键。 */
    var scriptVersionId: Long = 0,

    @Column(nullable = false, length = 100)
    /** 角色名称。 */
    var name: String = "",

    @Column(columnDefinition = "text")
    /** 角色外貌、身份和性格描述。 */
    var description: String? = null,

    @Column(columnDefinition = "text")
    /** 角色在故事中的核心动机。 */
    var motivation: String? = null,

    @Column(columnDefinition = "text")
    /** 角色之间的关系描述。 */
    var relationship: String? = null,

    @Column(name = "voice_profile_id")
    /** 关联的声线配置主键。 */
    var voiceProfileId: Long? = null,

    @Column(name = "asset_id")
    /** 关联的角色资产主键。 */
    var assetId: Long? = null,
)

/** 场次中的对白、旁白或内心独白。 */
@Entity
@Table(name = "script_dialogues")
class ScriptDialogue(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "script_scene_id", nullable = false)
    /** 所属场次主键。 */
    var scriptSceneId: Long = 0,

    @Column(name = "character_id")
    /** 说话角色主键；旁白等类型可以为空。 */
    var characterId: Long? = null,

    @Column(name = "sequence_no", nullable = false)
    /** 对白在场次中的顺序。 */
    var sequenceNo: Int = 0,

    @Column(nullable = false, columnDefinition = "text")
    /** 对白或旁白内容。 */
    var content: String = "",

    @Enumerated(EnumType.STRING)
    @Column(name = "dialogue_type", nullable = false, length = 30)
    /** 内容类型。 */
    var dialogueType: DialogueType = DialogueType.DIALOGUE,

    @Column(name = "duration_seconds", nullable = false)
    /** 播放预计时长，单位为秒。 */
    var durationSeconds: Int = 0,

    @Column(name = "voice_asset_id")
    /** 生成或录制的声音资产主键。 */
    var voiceAssetId: Long? = null,
)

/** 剧本质量门禁的一项规则结果。 */
@Entity
@Table(name = "script_quality_checks")
class ScriptQualityCheck(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "script_version_id", nullable = false)
    /** 被检查的剧本版本主键。 */
    var scriptVersionId: Long = 0,

    @Column(name = "rule_code", nullable = false, length = 50)
    /** 质量规则唯一编码。 */
    var ruleCode: String = "",

    @Column(nullable = false, length = 200)
    /** 规则展示标题。 */
    var title: String = "",

    @Column(length = 1000)
    /** 检查说明或改进建议。 */
    var note: String? = null,

    @Column(nullable = false)
    /** 规则是否通过。 */
    var passed: Boolean = false,

    @Column(precision = 4, scale = 2)
    /** 规则评分。 */
    var score: java.math.BigDecimal? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 检查结果创建时间。 */
    var createdAt: LocalDateTime? = null,
)

/** 剧本内容的语义类型。 */
enum class DialogueType {
    /** 角色对白。 */
    DIALOGUE,
    /** 画外旁白。 */
    NARRATION,
    /** 角色内心独白。 */
    INNER_MONOLOGUE,
    /** 以文字表示的音效提示。 */
    SFX_TEXT,
}

/** 剧本拆解任务状态。 */
enum class ScriptAnalysisStatus { NOT_PARSED, PROCESSING, COMPLETED, FAILED }
