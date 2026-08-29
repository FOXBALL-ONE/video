package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.ProjectVersion

/** 项目版本快照的数据访问接口。 */
interface ProjectVersionRepository : JpaRepository<ProjectVersion, Long> {
    /** 查询项目的全部版本，最新创建的版本优先。 */
    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: Long): List<ProjectVersion>

    /** 按项目和展示名称查询版本。 */
    fun findByProjectIdAndVersionName(projectId: Long, versionName: String): ProjectVersion?

    /** 查询项目最近创建的版本。 */
    fun findFirstByProjectIdOrderByCreatedAtDesc(projectId: Long): ProjectVersion?
}
