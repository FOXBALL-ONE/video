package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.EpisodeTaskCard
import top.foxball.video.entity.jdbc.EpisodeTaskCardRevision

interface EpisodeTaskCardRepository : JpaRepository<EpisodeTaskCard, Long> {
    fun findByProjectIdAndEpisodeId(projectId: Long, episodeId: Long): EpisodeTaskCard?

    fun findAllByProjectIdOrderByEpisodeIdAsc(projectId: Long): List<EpisodeTaskCard>
}

interface EpisodeTaskCardRevisionRepository : JpaRepository<EpisodeTaskCardRevision, Long> {
    fun findAllByTaskCardIdOrderByVersionNoDesc(taskCardId: Long): List<EpisodeTaskCardRevision>
}
