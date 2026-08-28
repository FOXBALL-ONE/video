package top.foxball.video.repository

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.Project
import top.foxball.video.entity.jdbc.ProjectStatus

/** 项目持久化访问接口，封装详情、列表及批量查询。 */
interface ProjectRepository : JpaRepository<Project, Long> {
    /** 查询单个项目并一次加载活动时间线，供详情页使用。 */
    @EntityGraph(attributePaths = ["activities"])
    fun findWithActivitiesById(id: Long): Project?

    /** 查询未处于指定状态的项目，并按最近更新时间倒序排列。 */
    fun findAllByStatusNotOrderByUpdatedAtDesc(status: ProjectStatus): List<Project>

    /** 按主键批量查询未处于指定状态的项目。 */
    fun findAllByIdInAndStatusNot(ids: Collection<Long>, status: ProjectStatus): List<Project>
}
