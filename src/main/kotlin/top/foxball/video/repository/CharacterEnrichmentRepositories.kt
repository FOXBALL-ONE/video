package top.foxball.video.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.stereotype.Repository
import top.foxball.video.entity.jdbc.CharacterEnrichmentResult

@Repository
interface CharacterEnrichmentResultRepository : JpaRepository<CharacterEnrichmentResult, Long> {
    fun findFirstByProjectIdAndEpisodeIdAndEpisodeAssetIdAndIdempotencyKey(
        projectId: Long,
        episodeId: Long,
        episodeAssetId: Long,
        idempotencyKey: String,
    ): CharacterEnrichmentResult?

    fun findByTaskId(taskId: Long): CharacterEnrichmentResult?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByTaskId(taskId: Long): CharacterEnrichmentResult?
}
