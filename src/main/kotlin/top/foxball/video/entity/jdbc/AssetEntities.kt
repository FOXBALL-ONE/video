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

/** 项目资产，例如角色、场景、道具、声音或音乐。 */
@Entity
@Table(name = "assets")
class Asset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(nullable = false, length = 100)
    /** 资产名称。 */
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    /** 资产业务类型。 */
    var type: AssetType = AssetType.PROP,

    @Column(columnDefinition = "text")
    /** 资产外观、用途或声音特征描述。 */
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    /** 资产当前工作状态。 */
    var status: AssetStatus = AssetStatus.DRAFT,

    @Column(name = "owner_id")
    /** 资产负责人的用户主键。 */
    var ownerId: Long? = null,

    @Column(name = "current_version_id")
    /** 当前生效的资产版本主键。 */
    var currentVersionId: Long? = null,

    @Column(name = "copyright_required", nullable = false)
    /** 是否必须提供版权授权信息。 */
    var copyrightRequired: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "copyright_status", nullable = false, length = 30)
    /** 资产版权审核状态。 */
    var copyrightStatus: CopyrightStatus = CopyrightStatus.NOT_REQUIRED,

    @Column(name = "voice_profile_id")
    /** 关联的声线配置主键。 */
    var voiceProfileId: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 资产创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 资产最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** 单集资产。用于隔离每一集中的角色、场景背景、道具和服饰等制作资料。 */
@Entity
@Table(name = "episode_assets")
class EpisodeAsset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0,
    @Column(name = "episode_id", nullable = false)
    var episodeId: Long = 0,
    @Column(nullable = false, length = 100)
    var name: String = "",
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    var type: EpisodeAssetType = EpisodeAssetType.PROP,
    @Column(columnDefinition = "text")
    var description: String? = null,
    @Column(name = "reference_url", length = 1000)
    var referenceUrl: String? = null,
    @Column(columnDefinition = "text")
    var notes: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AssetStatus = AssetStatus.DRAFT,
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,
    @Version
    var entityVersion: Long = 0,
)

enum class EpisodeAssetType { CHARACTER, SCENE_BACKGROUND, PROP, COSTUME, VOICE, MUSIC, SOUND_EFFECT }

/** 单集角色详情；承载角色资产在形象设计、三视图与图像生成阶段所需的扩展信息。 */
@Entity
@Table(name = "character_profiles")
class CharacterProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    var projectId: Long = 0,

    @Column(name = "episode_id", nullable = false)
    var episodeId: Long = 0,

    @Column(name = "episode_asset_id", nullable = false, unique = true)
    var episodeAssetId: Long = 0,

    @Column(name = "identity_title", length = 200)
    var identityTitle: String? = null,

    @Column(name = "role_tag", length = 50)
    var roleTag: String? = null,

    @Column(length = 50)
    var gender: String? = null,

    @Column(name = "age_stage", length = 50)
    var ageStage: String? = null,

    @Column(name = "age_note", length = 50)
    var ageNote: String? = null,

    @Column(name = "is_real_person", nullable = false)
    var isRealPerson: Boolean = false,

    @Column(name = "face_description", columnDefinition = "text")
    var faceDescription: String? = null,

    @Column(name = "costume_description", columnDefinition = "text")
    var costumeDescription: String? = null,

    @Column(name = "three_view_front_file_id", length = 64)
    var threeViewFrontFileId: String? = null,

    @Column(name = "three_view_side_file_id", length = 64)
    var threeViewSideFileId: String? = null,

    @Column(name = "three_view_back_file_id", length = 64)
    var threeViewBackFileId: String? = null,

    @Column(name = "three_view_merged_file_id", length = 64)
    var threeViewMergedFileId: String? = null,

    @Column(name = "expression_file_ids", columnDefinition = "text")
    var expressionFileIds: String = "[]",

    @Column(length = 100)
    var hairstyle: String? = null,

    @Column(length = 100)
    var costumeRef: String? = null,

    @Column(length = 100)
    var accessoryRef: String? = null,

    @Column(name = "voice_asset_id")
    var voiceAssetId: Long? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime? = null,

    @Version
    var entityVersion: Long = 0,
)

/** 资产生成或人工编辑的版本。 */
@Entity
@Table(name = "asset_versions")
class AssetVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    /** 所属资产主键。 */
    var assetId: Long = 0,

    @Column(name = "project_version_id")
    /** 关联的项目版本快照主键。 */
    var projectVersionId: Long? = null,

    @Column(name = "version_no", nullable = false)
    /** 资产版本递增序号。 */
    var versionNo: Int = 1,

    @Column(columnDefinition = "text")
    /** 此版本的资产描述。 */
    var description: String? = null,

    @Column(name = "generation_prompt", columnDefinition = "text")
    /** 生成该版本资产使用的正向提示词。 */
    var generationPrompt: String? = null,

    @Column(name = "negative_prompt", columnDefinition = "text")
    /** 生成该版本资产使用的反向提示词。 */
    var negativePrompt: String? = null,

    @Column(name = "media_file_id")
    /** 该版本对应的媒体文件主键。 */
    var mediaFileId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 资产版本状态。 */
    var status: AssetVersionStatus = AssetVersionStatus.DRAFT,

    @Column(name = "created_by")
    /** 创建该版本的用户主键。 */
    var createdBy: Long? = null,

    @Column(name = "locked_at")
    /** 版本锁定时间。 */
    var lockedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 版本创建时间。 */
    var createdAt: LocalDateTime? = null,
)

/** 资产版权与授权来源。 */
@Entity
@Table(name = "asset_licenses")
class AssetLicense(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    /** 被授权资产主键。 */
    var assetId: Long = 0,

    @Column(nullable = false, length = 500)
    /** 版权或授权来源说明。 */
    var source: String = "",

    @Column(name = "license_type", length = 100)
    /** 授权类型，例如 CC BY 或商业授权。 */
    var licenseType: String? = null,

    @Column(name = "license_url", length = 1000)
    /** 可核验授权页面地址。 */
    var licenseUrl: String? = null,

    @Column(length = 200)
    /** 权利人或授权方名称。 */
    var owner: String? = null,

    @Column(name = "expires_at")
    /** 授权到期时间。 */
    var expiresAt: LocalDateTime? = null,

    @Column(name = "verified_by")
    /** 审核版权的用户主键。 */
    var verifiedBy: Long? = null,

    @Column(name = "verified_at")
    /** 版权审核时间。 */
    var verifiedAt: LocalDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 版权记录审核状态。 */
    var status: LicenseStatus = LicenseStatus.PENDING,
)

/** 角色声线及 TTS 提供商绑定。 */
@Entity
@Table(name = "voice_profiles")
class VoiceProfile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "character_id")
    /** 关联剧本角色主键。 */
    var characterId: Long? = null,

    @Column(name = "provider_config_id")
    /** 关联 AI 模型提供商配置主键。 */
    var providerConfigId: Long? = null,

    @Column(name = "voice_id", length = 200)
    /** 提供商侧的声线 ID。 */
    var voiceId: String? = null,

    @Column(name = "voice_name", length = 200)
    /** 声线展示名称。 */
    var voiceName: String? = null,

    @Column(length = 30)
    /** 声线语言代码。 */
    var language: String = "zh-CN",

    @Column(nullable = false)
    /** 语速倍率。 */
    var speed: Double = 1.0,

    @Column(nullable = false)
    /** 音调偏移值。 */
    var pitch: Double = 0.0,

    @Column(columnDefinition = "text")
    /** 语气、情绪等风格描述。 */
    var style: String? = null,

    @Column(name = "sample_file_id")
    /** 声音示例文件主键。 */
    var sampleFileId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 声线绑定状态。 */
    var status: VoiceProfileStatus = VoiceProfileStatus.UNBOUND,
)

/** 资产缺口或完整性问题。 */
@Entity
@Table(name = "asset_issues")
class AssetIssue(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "asset_id")
    /** 关联资产主键，可为空表示项目级问题。 */
    var assetId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_type", nullable = false, length = 40)
    /** 缺口问题类型。 */
    var issueType: AssetIssueType = AssetIssueType.MISSING_INFORMATION,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 问题严重级别。 */
    var severity: IssueSeverity = IssueSeverity.WARNING,

    @Column(nullable = false, length = 1000)
    /** 问题具体描述。 */
    var description: String = "",

    @Column(nullable = false)
    /** 是否已经解决。 */
    var resolved: Boolean = false,

    @Column(name = "resolved_at")
    /** 问题解决时间。 */
    var resolvedAt: LocalDateTime? = null,

    @Column(name = "resolved_by")
    /** 解决问题的用户主键。 */
    var resolvedBy: Long? = null,
)

/** 可复用标签。 */
@Entity
@Table(name = "asset_tags")
class AssetTag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(nullable = false, length = 50)
    /** 标签名称。 */
    var name: String = "",
)

/** 资产与标签的关联。 */
@Entity
@Table(name = "asset_tag_relations")
class AssetTagRelation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "asset_id", nullable = false)
    /** 资产主键。 */
    var assetId: Long = 0,

    @Column(name = "tag_id", nullable = false)
    /** 标签主键。 */
    var tagId: Long = 0,
)

/** 资产业务类型。 */
enum class AssetType { CHARACTER, SCENE, PROP, VOICE, MUSIC, SOUND_EFFECT }
/** 资产工作流状态。 */
enum class AssetStatus { DRAFT, PENDING_CONFIRMATION, LOCKED, NEEDS_COPYRIGHT, TO_GENERATE, READY, ARCHIVED }
/** 资产版本状态。 */
enum class AssetVersionStatus { DRAFT, LOCKED, PUBLISHED, ARCHIVED }
/** 资产版权审核状态。 */
enum class CopyrightStatus { NOT_REQUIRED, PENDING, VERIFIED, REJECTED, EXPIRED }
/** 版权授权记录状态。 */
enum class LicenseStatus { PENDING, VERIFIED, REJECTED, EXPIRED }
/** 声线配置绑定状态。 */
enum class VoiceProfileStatus { UNBOUND, READY, ERROR }
/** 资产缺口问题类型。 */
enum class AssetIssueType { MISSING_INFORMATION, MISSING_REFERENCE, MISSING_COPYRIGHT, MISSING_VOICE, GENERATION_FAILED }
/** 问题严重级别。 */
enum class IssueSeverity { INFO, WARNING, ERROR }
