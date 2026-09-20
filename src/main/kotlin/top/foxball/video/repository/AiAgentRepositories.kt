package top.foxball.video.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import top.foxball.video.entity.jdbc.AiAgent
import top.foxball.video.entity.jdbc.AiAgentRun
import top.foxball.video.entity.jdbc.AiAgentVersion
import top.foxball.video.entity.jdbc.AgentRunStatus
import top.foxball.video.entity.jdbc.AgentScopeType
import top.foxball.video.entity.jdbc.AgentVersionStatus
import top.foxball.video.entity.jdbc.EpisodeWorkflowSnapshot
import top.foxball.video.entity.jdbc.ProductionStage

interface AiAgentRepository : JpaRepository<AiAgent, Long> {
    fun findAllByAgentKeyOrderByIdAsc(agentKey: String): List<AiAgent>

    fun findByAgentKeyAndScopeTypeAndProjectId(agentKey: String, scopeType: AgentScopeType, projectId: Long): AiAgent?

    fun findByAgentKeyAndScopeTypeAndOwnerUserId(agentKey: String, scopeType: AgentScopeType, ownerUserId: Long): AiAgent?

    fun findAllByEnabledTrueOrderByAgentKeyAsc(): List<AiAgent>

    fun findAllByScopeTypeAndEnabledTrueOrderByAgentKeyAsc(scopeType: AgentScopeType): List<AiAgent>

    fun findAllByScopeTypeAndProjectIdAndEnabledTrueOrderByAgentKeyAsc(
        scopeType: AgentScopeType,
        projectId: Long,
    ): List<AiAgent>
}

interface AiAgentVersionRepository : JpaRepository<AiAgentVersion, Long> {
    fun findByAgentIdOrderByVersionNoDesc(agentId: Long): List<AiAgentVersion>

    fun findByAgentIdAndVersionNo(agentId: Long, versionNo: Int): AiAgentVersion?

    fun findByAgentIdAndStatusOrderByVersionNoDesc(agentId: Long, status: AgentVersionStatus): List<AiAgentVersion>

    fun findByProductionStageAndStatusOrderByStageOrderAsc(
        productionStage: ProductionStage,
        status: AgentVersionStatus,
    ): List<AiAgentVersion>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): AiAgentVersion?
}

interface AiAgentRunRepository : JpaRepository<AiAgentRun, Long> {
    fun findByRequestId(requestId: String): AiAgentRun?

    fun findByEpisodeIdOrderByCreatedAtDesc(episodeId: Long): List<AiAgentRun>

    fun findByEpisodeIdAndProductionStageOrderByCreatedAtDesc(
        episodeId: Long,
        productionStage: ProductionStage,
    ): List<AiAgentRun>

    fun findByAgentVersionIdOrderByCreatedAtDesc(agentVersionId: Long): List<AiAgentRun>

    fun findByStatusOrderByCreatedAtDesc(status: AgentRunStatus): List<AiAgentRun>
}

interface EpisodeWorkflowSnapshotRepository : JpaRepository<EpisodeWorkflowSnapshot, Long> {
    fun findByEpisodeId(episodeId: Long): EpisodeWorkflowSnapshot?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByEpisodeId(episodeId: Long): EpisodeWorkflowSnapshot?
}
