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

/** 项目级制作快照，串联剧本、资产、分镜和视频产物的同一版本。 */
@Entity
@Table(name = "project_versions")
class ProjectVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 数据库主键。 */
    var id: Long? = null,

    @Column(name = "project_id", nullable = false)
    /** 所属项目主键。 */
    var projectId: Long = 0,

    @Column(name = "episode_id")
    /** 所属剧集主键；项目级发布快照时为空。 */
    var episodeId: Long? = null,

    @Column(name = "version_name", nullable = false, length = 20)
    /** 对外展示的版本名称，例如 v1.4。 */
    var versionName: String = "v0.1",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 版本生命周期状态。 */
    var status: VersionStatus = VersionStatus.DRAFT,

    @Column(length = 500)
    /** 版本说明或修订摘要。 */
    var description: String? = null,

    @Column(name = "created_by")
    /** 创建版本的用户主键。 */
    var createdBy: Long? = null,

    @Column(name = "locked_by")
    /** 锁定版本的用户主键。 */
    var lockedBy: Long? = null,

    @Column(name = "locked_at")
    /** 版本锁定时间。 */
    var lockedAt: LocalDateTime? = null,

    @Column(name = "published_at")
    /** 版本发布时间。 */
    var publishedAt: LocalDateTime? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    /** 版本创建时间。 */
    var createdAt: LocalDateTime? = null,

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    /** 版本最近更新时间。 */
    var updatedAt: LocalDateTime? = null,

    @Version
    /** JPA 乐观锁版本号。 */
    var entityVersion: Long = 0,
)

/** 项目版本的生命周期状态。 */
enum class VersionStatus {
    /** 可继续编辑的草稿版本。 */
    DRAFT,
    /** 已锁定、不可直接编辑的版本。 */
    LOCKED,
    /** 已发布并作为正式版本使用。 */
    PUBLISHED,
    /** 不再作为当前版本使用的历史版本。 */
    ARCHIVED,
}
