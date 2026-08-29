package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.Asset
import top.foxball.video.entity.jdbc.AssetIssue
import top.foxball.video.entity.jdbc.AssetLicense
import top.foxball.video.entity.jdbc.AssetTag
import top.foxball.video.entity.jdbc.AssetTagRelation
import top.foxball.video.entity.jdbc.AssetVersion
import top.foxball.video.entity.jdbc.AssetType
import top.foxball.video.entity.jdbc.VoiceProfile

/** 项目资产的数据访问接口。 */
interface AssetRepository : JpaRepository<Asset, Long> {
    /** 查询项目的全部资产，最近更新的资产优先。 */
    fun findAllByProjectIdOrderByUpdatedAtDesc(projectId: Long): List<Asset>

    /** 按类型查询项目资产。 */
    fun findAllByProjectIdAndTypeOrderByUpdatedAtDesc(projectId: Long, type: AssetType): List<Asset>

    /** 按名称查询项目资产。 */
    fun findByProjectIdAndName(projectId: Long, name: String): Asset?
}

/** 资产版本的数据访问接口。 */
interface AssetVersionRepository : JpaRepository<AssetVersion, Long> {
    /** 查询资产的全部版本，版本号倒序排列。 */
    fun findAllByAssetIdOrderByVersionNoDesc(assetId: Long): List<AssetVersion>

    /** 查询资产最新版本。 */
    fun findFirstByAssetIdOrderByVersionNoDesc(assetId: Long): AssetVersion?
}

/** 资产版权授权记录的数据访问接口。 */
interface AssetLicenseRepository : JpaRepository<AssetLicense, Long> {
    /** 查询资产的全部版权记录。 */
    fun findAllByAssetIdOrderByVerifiedAtDesc(assetId: Long): List<AssetLicense>
}

/** 角色声线配置的数据访问接口。 */
interface VoiceProfileRepository : JpaRepository<VoiceProfile, Long> {
    /** 查询项目声线配置，按名称排序。 */
    fun findAllByProjectIdOrderByVoiceNameAsc(projectId: Long): List<VoiceProfile>

    /** 查询角色绑定的声线。 */
    fun findByProjectIdAndCharacterId(projectId: Long, characterId: Long): VoiceProfile?
}

/** 资产缺口问题的数据访问接口。 */
interface AssetIssueRepository : JpaRepository<AssetIssue, Long> {
    /** 查询项目未解决的问题，严重级别优先。 */
    fun findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId: Long): List<AssetIssue>

    /** 查询指定资产未解决的问题。 */
    fun findAllByAssetIdAndResolvedFalseOrderBySeverityDesc(assetId: Long): List<AssetIssue>
}

/** 资产标签的数据访问接口。 */
interface AssetTagRepository : JpaRepository<AssetTag, Long> {
    /** 查询项目的全部标签。 */
    fun findAllByProjectIdOrderByNameAsc(projectId: Long): List<AssetTag>

    /** 按名称查询项目标签。 */
    fun findByProjectIdAndName(projectId: Long, name: String): AssetTag?
}

/** 资产与标签关联的数据访问接口。 */
interface AssetTagRelationRepository : JpaRepository<AssetTagRelation, Long> {
    /** 查询资产关联的全部标签关系。 */
    fun findAllByAssetId(assetId: Long): List<AssetTagRelation>

    /** 查询标签关联的全部资产关系。 */
    fun findAllByTagId(tagId: Long): List<AssetTagRelation>

    /** 判断资产是否已关联指定标签。 */
    fun existsByAssetIdAndTagId(assetId: Long, tagId: Long): Boolean
}
