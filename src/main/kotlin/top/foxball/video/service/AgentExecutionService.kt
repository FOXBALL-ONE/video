package top.foxball.video.service

import java.time.Duration
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import top.foxball.video.entity.jdbc.AgentRunStatus
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.repository.AiAgentRunRepository
import top.foxball.video.repository.ProductionTaskRepository

/** 智能体执行结果；输出已经经过智能体版本的输出适配。 */
data class AgentExecutionResult(
    val agentVersionId: Long,
    val templateVersionId: Long,
    val contentHash: String,
    val output: JsonNode,
    val audit: AgentRunAudit,
)

data class AgentRunAudit(
    val requestId: String,
    val model: String,
    val skills: List<String>,
    val mcpServers: List<String>,
    val toolCallCount: Int,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val attemptRounds: Int,
    val durationMs: Long,
)

/** 执行智能体并固定版本、模板、技能和 MCP 快照。 */
@Service
class AgentExecutionService(
    private val catalog: AgentCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val templateRunner: TemplateAgentRunner,
    private val schemaValidator: TemplateSchemaValidator,
    private val runRepository: AiAgentRunRepository,
    private val resultHandler: AgentResultHandler,
    private val workflowProgressService: WorkflowProgressService,
    private val productionTaskRepository: ProductionTaskRepository,
    private val objectMapper: ObjectMapper,
) {
    data class AgentExecutionRequest(
        val agentKey: String,
        val input: JsonNode,
        val providerConfigId: Long,
        val model: String,
        val requestId: String,
        val userId: Long? = null,
        val projectId: Long? = null,
        val episodeId: Long? = null,
        val taskId: Long? = null,
        val productionStage: top.foxball.video.entity.jdbc.ProductionStage? = null,
        val versionNo: Int? = null,
        val autoFillResult: Boolean = true,
    )

    /** 与模块设计文档一致的简化调用入口；扩展参数通过 [AgentExecutionRequest] 提供。 */
    fun execute(
        agentKey: String,
        input: JsonNode,
        providerConfigId: Long,
        model: String,
        requestId: String,
        userId: Long?,
        projectId: Long? = null,
    ): Mono<AgentExecutionResult> = execute(
        AgentExecutionRequest(agentKey, input, providerConfigId, model, requestId, userId, projectId),
    )

    fun execute(request: AgentExecutionRequest): Mono<AgentExecutionResult> =
        Mono.fromCallable { executeBlocking(request) }.subscribeOn(Schedulers.boundedElastic())

    @Transactional(readOnly = true)
    fun preview(agentKey: String, userId: Long?, projectId: Long?): AgentExecutionPlan {
        val resolved = catalog.resolveAgent(agentKey, userId, projectId)
        return AgentExecutionPlan(
            agentKey = resolved.agent.agentKey,
            displayName = resolved.agent.displayName,
            agentVersionId = resolved.version.id!!,
            versionNo = resolved.version.versionNo,
            templateKey = resolved.template.template.key,
            templateVersionId = resolved.template.version.id!!,
            skills = resolved.skillKeys,
            mcpServers = resolved.mcpBindings.map { it.key },
            builtinTools = resolved.builtinTools,
        )
    }

    private fun executeBlocking(request: AgentExecutionRequest): AgentExecutionResult {
        require(request.requestId.isNotBlank()) { "request_id 不能为空" }
        require(request.model.isNotBlank()) { "model 不能为空" }
        val resolved = if (request.versionNo == null) {
            catalog.resolveAgent(request.agentKey, request.userId, request.projectId)
        } else {
            catalog.resolveAgent(request.agentKey, request.versionNo, request.userId, request.projectId)
        }
        schemaValidator.validateOutput(resolved.version.inputSchemaJson, request.input)
        val productionTask = if (request.projectId != null && resolved.version.taskType != null) {
            val taskType = when (resolved.version.taskType!!.uppercase()) {
                "SCRIPT_PARSE", "SCRIPT_PARSER" -> ProductionTaskType.SCRIPT_ANALYSIS
                "CHARACTER_ENRICHER" -> ProductionTaskType.CHARACTER_ENRICHMENT
                "ASSET_ENRICHER", "ASSET_PARSER" -> ProductionTaskType.ASSET_EXTRACTION
                else -> runCatching { ProductionTaskType.valueOf(resolved.version.taskType!!.uppercase()) }.getOrNull()
            }
            taskType?.let {
                productionTaskRepository.save(
                    ProductionTask(
                        projectId = request.projectId,
                        episodeId = request.episodeId,
                        taskType = it,
                        status = ProductionTaskStatus.RUNNING,
                        progress = 0,
                        createdBy = request.userId,
                        providerConfigId = request.providerConfigId,
                        modelName = request.model,
                        correlationId = request.requestId,
                        inputPayload = mapOf("agent_key" to request.agentKey, "agent_version_id" to resolved.version.id, "input" to request.input.toString()),
                        startedAt = LocalDateTime.now(),
                    ),
                )
            }
        } else null
        val run = runRepository.save(
            top.foxball.video.entity.jdbc.AiAgentRun(
                agentVersionId = resolved.version.id!!,
                templateVersionId = resolved.version.templateVersionId,
                contentHash = resolved.version.contentHash,
                requestId = request.requestId,
                taskId = request.taskId,
                episodeId = request.episodeId,
                productionStage = request.productionStage ?: resolved.version.productionStage,
                productionTaskId = productionTask?.id,
                providerConfigId = request.providerConfigId,
                model = request.model,
                inputSummaryJson = summary(request.input),
                skillsJson = objectMapper.writeValueAsString(resolved.skillKeys),
                mcpServersJson = objectMapper.writeValueAsString(resolved.mcpBindings.map { it.key }),
                status = AgentRunStatus.FAILURE,
                createdBy = request.userId,
            ),
        )
        val startedAt = LocalDateTime.now()
        return try {
            val adaptedInput = applyMapping(request.input, resolved.version.inputMappingJson)
            val variables = adaptedInput.properties().associate { it.key to it.value }
            val rendered = templateRenderer.render(resolved.template, variables)
            val outcome = templateRunner.chat(
                TemplateAgentRunner.ChatRequest(
                    template = resolved.template,
                    rendered = rendered,
                    providerConfigId = request.providerConfigId,
                    model = request.model,
                    requestId = request.requestId,
                    additionalSkillKeys = resolved.skillKeys.filterNot { it in schemaValidator.skillKeys(resolved.template.version.skillsJson) },
                    additionalMcpServers = resolved.mcpBindings.map { it.key to it.tools },
                ),
            )
            val rawOutput = parseOutput(outcome.text)
            schemaValidator.validateOutput(resolved.template.version.outputSchemaJson, rawOutput)
            val output = applyMapping(rawOutput, resolved.version.outputMappingJson)
            schemaValidator.validateOutput(resolved.version.outputSchemaJson, output)
            val finishedAt = LocalDateTime.now()
            val duration = Duration.between(startedAt, finishedAt).toMillis()
            run.status = AgentRunStatus.SUCCESS
            run.completedAt = finishedAt
            run.durationMs = duration
            run.model = outcome.model
            run.inputTokens = outcome.inputTokens
            run.outputTokens = outcome.outputTokens
            run.toolCallCount = outcome.toolCallCount
            run.attemptRounds = outcome.attemptRounds
            run.outputSummaryJson = summary(output)
            runRepository.save(run)
            productionTask?.let {
                it.status = ProductionTaskStatus.SUCCEEDED
                it.progress = 100
                it.completedAt = finishedAt
                productionTaskRepository.save(it)
            }
            if (request.autoFillResult && request.episodeId != null) {
                resultHandler.handleResult(request.agentKey, output, request.episodeId, request.projectId, request.userId)
            }
            if (request.episodeId != null) {
                workflowProgressService.recordAgentCompletion(request.episodeId, request.agentKey)
            }
            AgentExecutionResult(
                agentVersionId = resolved.version.id!!,
                templateVersionId = resolved.version.templateVersionId,
                contentHash = resolved.version.contentHash,
                output = output,
                audit = AgentRunAudit(request.requestId, outcome.model, resolved.skillKeys, resolved.mcpBindings.map { it.key }, outcome.toolCallCount, outcome.inputTokens, outcome.outputTokens, outcome.attemptRounds, duration),
            )
        } catch (exception: Exception) {
            run.status = if (exception is java.util.concurrent.TimeoutException) AgentRunStatus.TIMEOUT else AgentRunStatus.FAILURE
            run.completedAt = LocalDateTime.now()
            run.durationMs = Duration.between(startedAt, run.completedAt).toMillis()
            run.errorCode = exception::class.simpleName
            run.errorMessage = exception.message
            runRepository.save(run)
            productionTask?.let {
                it.status = ProductionTaskStatus.FAILED
                it.errorMessage = exception.message
                it.completedAt = run.completedAt
                productionTaskRepository.save(it)
            }
            throw exception
        }
    }

    private fun parseOutput(text: String): JsonNode {
        val trimmed = text.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)?.groupValues?.get(1)?.trim()
        return objectMapper.readTree(fenced ?: trimmed)
    }

    private fun applyMapping(input: JsonNode, mappingJson: String): ObjectNode {
        val mapping = objectMapper.readTree(mappingJson)
        if (!mapping.isObject || mapping.size() == 0) return input as? ObjectNode ?: objectMapper.createObjectNode().set("value", input)
        val type = mapping.path("type").stringValue(null) ?: "direct"
        val mappings = mapping.path("mappings")
        if (!mappings.isObject) return input as? ObjectNode ?: objectMapper.createObjectNode().set("value", input)
        val result = objectMapper.createObjectNode()
        mappings.properties().forEach { (target, sourceNode) ->
            val source = sourceNode.stringValue(null) ?: return@forEach
            val value = if (type == "jsonpath") readPath(input, source) else readPath(input, if (source.startsWith("$")) source else "$.${source.removePrefix(".")}")
            if (value != null && !value.isMissingNode) result.set(target, value)
        }
        mapping.path("defaults").takeIf { it.isObject }?.properties()?.forEach { (key, value) -> if (!result.has(key)) result.set(key, value) }
        return result
    }

    private fun readPath(root: JsonNode, expression: String): JsonNode? {
        val normalized = expression.trim().removePrefix("$").removePrefix(".")
        if (normalized.isBlank()) return root
        var current: JsonNode? = root
        normalized.split('.').filter { it.isNotBlank() }.forEach { part -> current = current?.get(part) }
        return current
    }

    private fun summary(node: JsonNode): String = objectMapper.writeValueAsString(
        mapOf(
            "type" to when { node.isObject -> "object"; node.isArray -> "array"; else -> "value" },
            "fields" to if (node.isObject) node.properties().map { it.key }.toList() else emptyList<String>(),
            "size" to if (node.isArray) node.size() else node.toString().length,
        ),
    )
}

data class AgentExecutionPlan(
    val agentKey: String,
    val displayName: String,
    val agentVersionId: Long,
    val versionNo: Int,
    val templateKey: String,
    val templateVersionId: Long,
    val skills: List<String>,
    val mcpServers: List<String>,
    val builtinTools: List<String>,
)
