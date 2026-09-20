package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.EpisodeWorkflowSnapshot
import top.foxball.video.entity.jdbc.ProductionStage
import top.foxball.video.entity.jdbc.AgentVersionStatus
import top.foxball.video.repository.AiAgentRepository
import top.foxball.video.repository.AiAgentVersionRepository
import top.foxball.video.repository.EpisodeRepository
import top.foxball.video.repository.EpisodeWorkflowSnapshotRepository

/** Episode 线性工作流的状态管理。 */
@Service
class WorkflowProgressService(
    private val episodeRepository: EpisodeRepository,
    private val snapshotRepository: EpisodeWorkflowSnapshotRepository,
    private val agentRepository: AiAgentRepository,
    private val versionRepository: AiAgentVersionRepository,
    private val objectMapper: ObjectMapper,
) {
    data class WorkflowState(
        val episodeId: Long,
        val currentStage: ProductionStage,
        val executedAgents: List<String>,
        val stageProgress: Map<String, Int>,
        val pendingAgents: List<String>,
    )

    data class StageInfo(
        val stage: ProductionStage,
        val progress: Int,
        val totalAgents: Int,
        val completedAgents: Int,
        val pendingAgents: List<String>,
    )

    @Transactional
    fun initializeWorkflow(episodeId: Long): WorkflowState {
        require(episodeRepository.existsById(episodeId)) { "剧集不存在：$episodeId" }
        val snapshot = snapshotRepository.findByEpisodeId(episodeId) ?: snapshotRepository.save(
            EpisodeWorkflowSnapshot(
                episodeId = episodeId,
                currentStage = ProductionStage.SCRIPT,
                stageProgressJson = objectMapper.writeValueAsString(ProductionStage.entries.associate { it.name to 0 }),
            ),
        )
        refreshPending(snapshot)
        snapshotRepository.save(snapshot)
        return stateOf(snapshot)
    }

    @Transactional(readOnly = true)
    fun getWorkflowState(episodeId: Long): WorkflowState? = snapshotRepository.findByEpisodeId(episodeId)?.let(::stateOf)

    @Transactional(readOnly = true)
    fun getAllStagesInfo(episodeId: Long): List<StageInfo> {
        val snapshot = snapshotRepository.findByEpisodeId(episodeId) ?: return emptyList()
        val progress = readProgress(snapshot)
        return ProductionStage.entries.map { stage -> stageInfo(snapshot, stage, progress[stage.name] ?: 0) }
    }

    @Transactional(readOnly = true)
    fun getStageInfo(episodeId: Long, stage: ProductionStage): StageInfo {
        val snapshot = snapshotRepository.findByEpisodeId(episodeId) ?: throw IllegalArgumentException("工作流不存在：$episodeId")
        val progress = readProgress(snapshot)
        return stageInfo(snapshot, stage, progress[stage.name] ?: 0)
    }

    @Transactional
    fun recordAgentCompletion(episodeId: Long, agentKey: String): WorkflowState {
        val snapshot = snapshotRepository.findWithLockByEpisodeId(episodeId)
            ?: initializeWorkflow(episodeId).let { snapshotRepository.findWithLockByEpisodeId(episodeId)!! }
        val executed = readStrings(snapshot.executedAgentsJson).toMutableList()
        if (agentKey !in executed) executed += agentKey
        snapshot.executedAgentsJson = objectMapper.writeValueAsString(executed)
        updateProgress(snapshot)
        refreshPending(snapshot)
        return stateOf(snapshotRepository.save(snapshot))
    }

    @Transactional(readOnly = true)
    fun canAdvanceStage(episodeId: Long, targetStage: ProductionStage): Boolean {
        val snapshot = snapshotRepository.findByEpisodeId(episodeId) ?: return false
        val current = snapshot.currentStage
        val expected = ProductionStage.entries.getOrNull(current.ordinal + 1) ?: return false
        if (targetStage != expected) return false
        return (readProgress(snapshot)[current.name] ?: 0) >= 100
    }

    @Transactional
    fun advanceStage(episodeId: Long, userId: Long?): ProductionStage {
        val snapshot = snapshotRepository.findWithLockByEpisodeId(episodeId)
            ?: throw IllegalArgumentException("工作流不存在：$episodeId")
        val next = ProductionStage.entries.getOrNull(snapshot.currentStage.ordinal + 1)
            ?: throw IllegalStateException("工作流已经处于最后阶段")
        require(canAdvanceStage(episodeId, next)) { "当前阶段尚未完成" }
        snapshot.currentStage = next
        refreshPending(snapshot)
        val saved = snapshotRepository.save(snapshot)
        episodeRepository.findById(episodeId).ifPresent { episode -> episode.currentStage = next; episodeRepository.save(episode) }
        return saved.currentStage
    }

    private fun stateOf(snapshot: EpisodeWorkflowSnapshot): WorkflowState = WorkflowState(
        episodeId = snapshot.episodeId,
        currentStage = snapshot.currentStage,
        executedAgents = readStrings(snapshot.executedAgentsJson),
        stageProgress = readProgress(snapshot),
        pendingAgents = readStrings(snapshot.pendingAgentsJson),
    )

    private fun stageInfo(snapshot: EpisodeWorkflowSnapshot, stage: ProductionStage, progress: Int): StageInfo {
        val published = versionRepository.findByProductionStageAndStatusOrderByStageOrderAsc(stage, AgentVersionStatus.PUBLISHED)
        val keys = published.mapNotNull { agentRepository.findById(it.agentId).orElse(null)?.agentKey }
        val executed = readStrings(snapshot.executedAgentsJson).toSet()
        return StageInfo(stage, progress, keys.size, keys.count { it in executed }, keys.filterNot { it in executed })
    }

    private fun updateProgress(snapshot: EpisodeWorkflowSnapshot) {
        val executed = readStrings(snapshot.executedAgentsJson).toSet()
        val progress = readProgress(snapshot).toMutableMap()
        ProductionStage.entries.forEach { stage ->
            val keys = versionRepository.findByProductionStageAndStatusOrderByStageOrderAsc(stage, AgentVersionStatus.PUBLISHED)
                .mapNotNull { agentRepository.findById(it.agentId).orElse(null)?.agentKey }
                .distinct()
            progress[stage.name] = if (keys.isEmpty()) 0 else (keys.count { it in executed } * 100 / keys.size).coerceIn(0, 100)
        }
        snapshot.stageProgressJson = objectMapper.writeValueAsString(progress)
    }

    private fun refreshPending(snapshot: EpisodeWorkflowSnapshot) {
        val executed = readStrings(snapshot.executedAgentsJson).toSet()
        val pending = versionRepository.findByProductionStageAndStatusOrderByStageOrderAsc(snapshot.currentStage, AgentVersionStatus.PUBLISHED)
            .mapNotNull { agentRepository.findById(it.agentId).orElse(null)?.agentKey }
            .distinct()
            .filterNot { it in executed }
        snapshot.pendingAgentsJson = objectMapper.writeValueAsString(pending)
    }

    private fun readStrings(json: String): List<String> = runCatching {
        val node = objectMapper.readTree(json)
        if (!node.isArray) emptyList() else node.mapNotNull { it.stringValue(null) }
    }.getOrDefault(emptyList())

    private fun readProgress(snapshot: EpisodeWorkflowSnapshot): Map<String, Int> = runCatching {
        val node = objectMapper.readTree(snapshot.stageProgressJson)
        if (!node.isObject) emptyMap() else node.properties().associate { it.key to it.value.intValue() }
    }.getOrDefault(emptyMap())
}
