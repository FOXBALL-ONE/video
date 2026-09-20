package top.foxball.video.service

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.AgentRunStatus
import top.foxball.video.entity.jdbc.ProductionStage

/** 线性执行多个智能体，并把上一步输出放入下一步上下文。 */
@Service
class WorkflowExecutionService(
    private val agentExecutionService: AgentExecutionService,
    private val catalog: AgentCatalogService,
    private val objectMapper: ObjectMapper,
) {
    data class AgentChainRequest(
        val episodeId: Long,
        val projectId: Long?,
        val steps: List<AgentChainStep>,
        val initialInput: JsonNode,
        val providerConfigId: Long,
        val model: String,
        val userId: Long?,
        val productionStage: ProductionStage?,
        val onFailure: FailurePolicy = FailurePolicy.STOP,
        val autoFillResult: Boolean = true,
    )

    data class AgentChainStep(val agentKey: String, val inputMapping: Map<String, String> = emptyMap())

    data class AgentChainResult(
        val status: ChainStatus,
        val results: List<AgentExecutionResult>,
        val failedAtStep: Int? = null,
        val errorMessage: String? = null,
    )

    enum class FailurePolicy { STOP, CONTINUE, SKIP }
    enum class ChainStatus { SUCCESS, FAILED, PARTIAL }

    @Transactional
    fun executeChain(request: AgentChainRequest): AgentChainResult {
        var context = request.initialInput
        val results = mutableListOf<AgentExecutionResult>()
        request.steps.forEachIndexed { index, step ->
            val input = applyInputMapping(context, step.inputMapping)
            val result = try {
                agentExecutionService.execute(
                    AgentExecutionService.AgentExecutionRequest(
                        agentKey = step.agentKey,
                        input = input,
                        providerConfigId = request.providerConfigId,
                        model = request.model,
                        requestId = UUID.randomUUID().toString(),
                        userId = request.userId,
                        projectId = request.projectId,
                        episodeId = request.episodeId,
                        productionStage = request.productionStage,
                        autoFillResult = request.autoFillResult,
                    ),
                ).block()!!
            } catch (exception: Exception) {
                if (request.onFailure == FailurePolicy.STOP) return AgentChainResult(ChainStatus.FAILED, results, index, exception.message)
                if (request.onFailure == FailurePolicy.SKIP) return@forEachIndexed
                return@forEachIndexed
            }
            results += result
            context = objectMapper.createObjectNode().apply {
                set("input", context)
                set(step.agentKey, result.output)
                set("output", result.output)
            }
            if (result.audit.requestId.isBlank()) return AgentChainResult(ChainStatus.PARTIAL, results, index)
        }
        return AgentChainResult(if (results.size == request.steps.size) ChainStatus.SUCCESS else ChainStatus.PARTIAL, results)
    }

    @Transactional
    fun executeStageWorkflow(
        episodeId: Long,
        projectId: Long?,
        stage: ProductionStage,
        initialInput: JsonNode,
        providerConfigId: Long,
        model: String,
        userId: Long?,
    ): AgentChainResult {
        val steps = catalog.listStageVersions(stage).mapNotNull { version ->
            catalog.listAgents().firstOrNull { it.id == version.agentId }?.agentKey?.let { AgentChainStep(it) }
        }
        require(steps.isNotEmpty()) { "阶段没有已发布智能体：$stage" }
        return executeChain(AgentChainRequest(episodeId, projectId, steps, initialInput, providerConfigId, model, userId, stage))
    }

    private fun applyInputMapping(context: JsonNode, mapping: Map<String, String>): JsonNode {
        if (mapping.isEmpty()) return context
        val result = objectMapper.createObjectNode()
        mapping.forEach { (target, path) ->
            val normalized = path.removePrefix("$").removePrefix(".")
            var value: JsonNode? = context
            normalized.split('.').filter { it.isNotBlank() }.forEach { segment -> value = value?.get(segment) }
            if (value != null && !value!!.isMissingNode) result.set(target, value)
        }
        return result
    }
}
