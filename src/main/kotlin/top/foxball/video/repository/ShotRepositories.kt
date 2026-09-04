package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.Shot
import top.foxball.video.entity.jdbc.ShotAsset
import top.foxball.video.entity.jdbc.ShotContinuityIssue
import top.foxball.video.entity.jdbc.ShotGroup

/** 分镜镜头的数据访问接口。 */
interface ShotRepository : JpaRepository<Shot, Long> {
    /** 查询项目的全部镜头，按镜头顺序排列。 */
    fun findAllByProjectIdOrderBySequenceNoAsc(projectId: Long): List<Shot>

    /** 查询未绑定剧集的项目级镜头，按镜头顺序排列。 */
    fun findAllByProjectIdAndEpisodeIdIsNullOrderBySequenceNoAsc(projectId: Long): List<Shot>

    /** 查询项目版本的全部镜头，按镜头顺序排列。 */
    fun findAllByProjectVersionIdOrderBySequenceNoAsc(projectVersionId: Long): List<Shot>

    /** 查询项目指定顺序号的镜头。 */
    fun findByProjectIdAndSequenceNo(projectId: Long, sequenceNo: Int): Shot?

    fun findAllByProjectIdAndEpisodeIdOrderBySequenceNoAsc(projectId: Long, episodeId: Long): List<Shot>

    /** 查询分镜脚本分组下的全部镜头。 */
    fun findAllByShotGroupId(shotGroupId: Long): List<Shot>
}

/** 分镜脚本分组的数据访问接口。 */
interface ShotGroupRepository : JpaRepository<ShotGroup, Long> {
    /** 查询项目的全部分镜脚本分组，按展示顺序排列。 */
    fun findAllByProjectIdOrderBySortOrderAsc(projectId: Long): List<ShotGroup>

    /** 查询未绑定剧集的项目级分镜脚本分组。 */
    fun findAllByProjectIdAndEpisodeIdIsNullOrderBySortOrderAsc(projectId: Long): List<ShotGroup>

    fun findAllByProjectIdAndEpisodeIdOrderBySortOrderAsc(projectId: Long, episodeId: Long): List<ShotGroup>

    fun findByIdAndProjectId(id: Long, projectId: Long): ShotGroup?
}

/** 分镜与资产关联的数据访问接口。 */
interface ShotAssetRepository : JpaRepository<ShotAsset, Long> {
    /** 查询镜头引用的全部资产。 */
    fun findAllByShotIdOrderBySortOrderAsc(shotId: Long): List<ShotAsset>

    /** 查询资产被引用的全部镜头。 */
    fun findAllByAssetIdOrderByShotIdAsc(assetId: Long): List<ShotAsset>

    /** 判断镜头是否已引用指定资产。 */
    fun existsByShotIdAndAssetId(shotId: Long, assetId: Long): Boolean
}

/** 分镜连续性问题的数据访问接口。 */
interface ShotContinuityIssueRepository : JpaRepository<ShotContinuityIssue, Long> {
    /** 查询项目未解决的连续性问题。 */
    fun findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId: Long): List<ShotContinuityIssue>

    /** 查询两个相邻镜头之间的问题。 */
    fun findAllByFromShotIdAndToShotId(fromShotId: Long, toShotId: Long): List<ShotContinuityIssue>
}
