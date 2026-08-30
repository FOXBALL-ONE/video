package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.Episode

/** 项目剧集持久化访问接口。 */
interface EpisodeRepository : JpaRepository<Episode, Long> {
    fun findAllByProjectIdOrderByEpisodeNoAsc(projectId: Long): List<Episode>

    fun findByProjectIdAndEpisodeNo(projectId: Long, episodeNo: Int): Episode?

    fun findByIdAndProjectId(id: Long, projectId: Long): Episode?
}
