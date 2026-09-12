package top.foxball.video.service

import jakarta.annotation.PreDestroy
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.ai.AiClientException
import top.foxball.video.ai.AiConfigurationException
import top.foxball.video.ai.AiRateLimitException
import top.foxball.video.ai.AiUpstreamAuthenticationException
import top.foxball.video.ai.AiUpstreamException
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.CharacterEnrichmentResult
import top.foxball.video.entity.jdbc.CharacterEnrichmentResultStatus
import top.foxball.video.entity.jdbc.CharacterProfile
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.ProductionTask
import top.foxball.video.entity.jdbc.ProductionTaskStatus
import top.foxball.video.entity.jdbc.ProductionTaskType
import top.foxball.video.repository.AiModelProviderConfigRepository
import top.foxball.video.repository.CharacterEnrichmentResultRepository
import top.foxball.video.repository.EpisodeAssetRepository
import top.foxball.video.repository.ProjectRepository
import top.foxball.video.repository.ProductionTaskRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 用户手动触发的角色内容丰富任务；执行结果必须经采纳后才写入角色资产。 */
@Service
class CharacterEnrichmentTaskService(
    private val projectRepository: ProjectRepository,
    private val episodeAssetRepository: EpisodeAssetRepository,
    private val taskRepository: ProductionTaskRepository,
    private val resultRepository: CharacterEnrichmentResultRepository,
    private val providerConfigRepository: AiModelProviderConfigRepository,
    private val taskCenterService: TaskCenterService,
    private val scriptService: ScriptService,
    private val templateCatalog: TemplateCatalogService,
    private val templateRenderer: TemplateRenderer,
    private val templateSchemaValidator: TemplateSchemaValidator,
    private val templateAgentRunner: TemplateAgentRunner,
    private val templateRunAuditService: TemplateRunAuditService,
    private val assetService: AssetService,
    private val objectMapper: ObjectMapper,
) {
    data class Submission(val task: ProductionTask, val result: CharacterEnrichmentResult, val existing: Boolean)

    data class Detail(val task: ProductionTask, val result: CharacterEnrichmentResult)

    data class Adoption(val task: ProductionTask, val result: CharacterEnrichmentResult, val assetId: Long)

    class QueueFullException : IllegalStateException("角色扩写任务队列已满，请稍后重试")

    private val executor: ExecutorService = ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(16),
        Executors.defaultThreadFactory(),
        ThreadPoolExecutor.AbortPolicy(),
    )

    @Transactional
    fun submit(
        projectId: Long,
        userId: Long?,
        episodeId: Long,
        episodeAssetId: Long,
        providerConfigId: Long,
        model: String,
        idempotencyKey: String?,
    ): Submission? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(episodeAssetId, projectId, episodeId) ?: return null
        require(asset.type == EpisodeAssetType.CHARACTER) { "只有角色资产可以执行内容丰富" }
        require(providerConfigId > 0) { "提供商配置不正确" }
        require(model.trim().isNotBlank()) { "模型不能为空" }
        val config = providerConfigRepository.findById(providerConfigId).orElse(null) ?: throw IllegalArgumentException("AI 提供商配置不存在")
        require(config.enabled) { "AI 提供商配置已停用" }
        val normalizedKey = idempotencyKey?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedKey != null) {
            val existing = resultRepository.findFirstByProjectIdAndEpisodeIdAndEpisodeAssetIdAndIdempotencyKey(projectId, episodeId, episodeAssetId, normalizedKey)
            if (existing != null) {
                val existingTask = taskRepository.findById(existing.taskId).orElse(null) ?: return null
                if (existingTask.providerConfigId != providerConfigId || existingTask.modelName != model.trim()) throw IllegalArgumentException("幂等键已用于不同的角色扩写请求")
                return Submission(existingTask, existing, true)
            }
        }
        if ((executor as ThreadPoolExecutor).queue.remainingCapacity() <= 0) throw QueueFullException()
        val template = templateCatalog.resolve("character_enrichment", projectId, userId, "zh-CN")
        val scriptSnapshot = scriptService.loadExisting(projectId, episodeId)
        val scriptVersionId = scriptSnapshot?.version?.id
        val contentHash = scriptSnapshot?.version?.content?.let(::contentHashOf)
        val task = taskRepository.save(
            ProductionTask(
                projectId = projectId,
                createdBy = userId,
                episodeId = episodeId,
                taskType = ProductionTaskType.CHARACTER_ENRICHMENT,
                status = ProductionTaskStatus.QUEUED,
                title = "角色内容丰富",
                providerConfigId = providerConfigId,
                modelName = model.trim(),
                correlationId = UUID.randomUUID().toString(),
                idempotencyKey = normalizedKey,
                inputPayload = mapOf(
                    "prompt_key" to "character_enrichment",
                    "prompt_version_id" to requireNotNull(template.version.id),
                    "template_hash" to template.version.contentHash,
                    "output_schema_version" to "character_enrichment.v1",
                    "episode_asset_id" to episodeAssetId,
                    "script_version_id" to scriptVersionId,
                    "content_hash" to contentHash,
                    "provider_config_id" to providerConfigId,
                    "model" to model.trim(),
                    "summary" to "生成角色卡草稿，等待用户采纳",
                ),
            ),
        )
        val result = resultRepository.save(
            CharacterEnrichmentResult(
                taskId = requireNotNull(task.id),
                projectId = projectId,
                episodeId = episodeId,
                episodeAssetId = episodeAssetId,
                scriptVersionId = scriptVersionId,
                contentHash = contentHash,
                promptVersionId = requireNotNull(template.version.id),
                idempotencyKey = normalizedKey,
            ),
        )
        taskCenterService.recordEnqueued(listOf(task))
        val taskId = requireNotNull(task.id)
        val dispatch = {
            try {
                executor.execute { execute(taskId) }
            } catch (_: RejectedExecutionException) {
                taskCenterService.markFailed(taskId, "TASK_QUEUE_FULL", "角色扩写任务队列已满，请稍后重试")
            }
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    dispatch()
                }
            })
        } else {
            dispatch()
        }
        return Submission(task, result, false)
    }

    @Transactional(readOnly = true)
    fun detail(projectId: Long, userId: Long?, taskId: Long): Detail? {
        if (!taskCenterService.canReadProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        if (task.taskType != ProductionTaskType.CHARACTER_ENRICHMENT) return null
        val result = resultRepository.findByTaskId(taskId) ?: return null
        if (result.projectId != projectId) return null
        return Detail(task, result)
    }

    @Transactional
    fun discard(projectId: Long, userId: Long?, taskId: Long): Detail? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return null
        if (task.taskType != ProductionTaskType.CHARACTER_ENRICHMENT || result.projectId != projectId) return null
        if (task.status != ProductionTaskStatus.SUCCEEDED) throw IllegalStateException("角色扩写任务尚未成功")
        if (result.resultStatus == CharacterEnrichmentResultStatus.ADOPTED) throw IllegalStateException("角色扩写结果已采纳，不能放弃")
        result.resultStatus = CharacterEnrichmentResultStatus.DISCARDED
        result.discardedAt = LocalDateTime.now()
        return Detail(task, resultRepository.save(result))
    }

    @Transactional
    fun adopt(projectId: Long, userId: Long?, taskId: Long, idempotencyKey: String?): Adoption? {
        if (!taskCenterService.canWriteProject(projectId, userId)) return null
        val task = taskRepository.findByIdAndProjectId(taskId, projectId) ?: return null
        val result = resultRepository.findWithLockByTaskId(taskId) ?: return null
        if (task.taskType != ProductionTaskType.CHARACTER_ENRICHMENT || result.projectId != projectId) return null
        if (task.status != ProductionTaskStatus.SUCCEEDED || result.resultStatus !in setOf(CharacterEnrichmentResultStatus.STAGED, CharacterEnrichmentResultStatus.ADOPTED)) {
            throw IllegalStateException("角色扩写结果尚不可采纳")
        }
        val normalizedKey = idempotencyKey?.trim()?.takeIf { it.isNotBlank() }
        if (result.resultStatus == CharacterEnrichmentResultStatus.ADOPTED) {
            if (normalizedKey != null && result.adoptionIdempotencyKey != null && normalizedKey != result.adoptionIdempotencyKey) throw IllegalArgumentException("结果已使用其他幂等键采纳")
            return Adoption(task, result, result.episodeAssetId)
        }
        if (normalizedKey != null && result.adoptionIdempotencyKey == normalizedKey) return Adoption(task, result, result.episodeAssetId)
        val snapshot = scriptService.loadExisting(projectId, result.episodeId)
        if (result.scriptVersionId != null && (snapshot?.version?.id != result.scriptVersionId || snapshot?.version?.content?.let(::contentHashOf) != result.contentHash)) {
            throw IllegalStateException("剧本内容已变化，请重新执行角色扩写")
        }
        val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(result.episodeAssetId, projectId, result.episodeId) ?: return null
        if (asset.type != EpisodeAssetType.CHARACTER) return null
        val root = objectMapper.readTree(result.resultJson ?: throw IllegalStateException("角色扩写结果为空"))
        val appearance = root.path("appearance")
        val identity = root.path("identity")
        val profile = assetService.loadCharacterProfile(projectId, result.episodeId, result.episodeAssetId) ?: return null
        val description = listOfNotNull(
            identity.path("occupation").stringValue(null),
            identity.path("social_status").stringValue(null),
            root.path("dramatic_function").path("core_want").stringValue(null),
            root.path("dramatic_function").path("internal_conflict").stringValue(null),
        ).joinToString("；").ifBlank { asset.description.orEmpty() }
        val savedProfile = assetService.saveCharacterProfile(
            projectId,
            result.episodeId,
            result.episodeAssetId,
            AssetService.CharacterProfileCommand(
                description = description,
                identityTitle = identity.path("occupation").stringValue(null),
                ageStage = identity.path("age_range").stringValue(null),
                faceDescription = appearance.path("face").stringValue(null),
                costumeDescription = appearance.path("costume").stringValue(null),
                hairstyle = appearance.path("hair").stringValue(null),
            ),
        ) ?: return null
        savedProfile.asset.notes = result.resultJson?.take(100_000)
        episodeAssetRepository.save(savedProfile.asset)
        result.resultStatus = CharacterEnrichmentResultStatus.ADOPTED
        result.adoptionIdempotencyKey = normalizedKey
        result.adoptedAt = LocalDateTime.now()
        return Adoption(task, resultRepository.save(result), result.episodeAssetId)
    }

    private fun execute(taskId: Long) {
        val started = runCatching { taskCenterService.markRunning(taskId) }.getOrNull() ?: return
        if (started.status != ProductionTaskStatus.RUNNING) return
        val result = resultRepository.findByTaskId(taskId) ?: run {
            taskCenterService.markFailed(taskId, "MISSING_ENRICHMENT_RESULT", "角色扩写结果记录不存在")
            return
        }
        val requestId = UUID.randomUUID().toString()
        val startedAt = System.nanoTime()
        var rendered: TemplateRenderer.RenderedTemplate? = null
        try {
            val template = templateCatalog.resolveExecutableVersion(result.promptVersionId) ?: throw IllegalArgumentException("模板版本不存在")
            require(started.inputPayload["template_hash"]?.toString().equals(template.version.contentHash, ignoreCase = true)) { "模板版本内容已变化" }
            val asset = episodeAssetRepository.findByIdAndProjectIdAndEpisodeId(result.episodeAssetId, result.projectId, result.episodeId) ?: throw IllegalArgumentException("角色资产不存在")
            require(asset.type == EpisodeAssetType.CHARACTER) { "只有角色资产可以执行内容丰富" }
            val script = scriptService.loadExisting(result.projectId, result.episodeId)
            if (result.scriptVersionId != null && (script?.version?.id != result.scriptVersionId || script?.version?.content?.let(::contentHashOf) != result.contentHash)) throw IllegalStateException("剧本内容已变化，请重新执行角色扩写")
            val profile = assetService.loadCharacterProfile(result.projectId, result.episodeId, result.episodeAssetId)?.profile ?: CharacterProfile(projectId = result.projectId, episodeId = result.episodeId, episodeAssetId = result.episodeAssetId)
            val project = projectRepository.findById(result.projectId).orElse(null) ?: throw IllegalArgumentException("项目不存在")
            val variables = mapOf(
                "character_context_json" to objectMapper.writeValueAsString(mapOf("asset" to asset, "profile" to profile)),
                "script_evidence_json" to objectMapper.writeValueAsString(mapOf("characters" to script?.characters.orEmpty(), "dialogues" to script?.dialogues.orEmpty(), "scenes" to script?.scenes.orEmpty())),
                "project_style_json" to objectMapper.writeValueAsString(mapOf("type" to project.type, "description" to project.description)),
                "existing_character_card_json" to objectMapper.writeValueAsString(mapOf("description" to asset.description, "notes" to asset.notes)),
                "output_language" to "zh-CN",
            )
            val renderedPrompt = templateRenderer.render(template, variables)
            rendered = renderedPrompt
            val outcome = templateAgentRunner.chat(
                TemplateAgentRunner.ChatRequest(
                    template = template,
                    rendered = renderedPrompt,
                    providerConfigId = requireNotNull(started.providerConfigId),
                    model = requireNotNull(started.modelName),
                    requestId = requestId,
                ),
            )
            val completion = templateAgentRunner.toCompletionResult(outcome)
            val root = parseRoot(completion.primaryChoice?.message?.content.orEmpty())
            templateSchemaValidator.validateOutput(template.version.outputSchemaJson, root)
            val resultJson = objectMapper.writeValueAsString(root)
            require(resultJson.toByteArray(StandardCharsets.UTF_8).size <= 2_000_000) { "模型返回结果超过大小限制" }
            result.resultJson = resultJson
            result.resultHash = contentHashOf(resultJson)
            result.resultStatus = CharacterEnrichmentResultStatus.STAGED
            result.errorCode = null
            result.errorMessage = null
            resultRepository.save(result)
            taskCenterService.markSucceeded(taskId, "RESULT_STAGED")
            runCatching {
                templateRunAuditService.record(
                    TemplateRunRecord(
                        templateKey = template.template.key,
                        templateVersionId = result.promptVersionId,
                        taskId = taskId,
                        invocationId = requestId,
                        providerConfigId = started.providerConfigId,
                        model = requireNotNull(started.modelName),
                        status = "SUCCEEDED",
                        attempt = started.attempt,
                        latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                        inputTokens = outcome.inputTokens ?: rendered?.inputTokens,
                        outputTokens = outcome.outputTokens,
                        templateHash = rendered?.templateHash,
                        variablesHash = rendered?.variablesHash,
                        renderedInputHash = rendered?.renderedInputHash,
                        skillKeys = outcome.skillKeys,
                        mcpServerKeys = outcome.mcpServerKeys,
                        toolCallCount = outcome.toolCallCount,
                    ),
                )
            }
        } catch (exception: AiConfigurationException) {
            fail(taskId, result, "AI_CONFIGURATION_ERROR", "AI 提供商配置不可用", started, requestId, startedAt, rendered)
        } catch (exception: AiClientException) {
            val code = when (exception) {
                is AiRateLimitException -> "AI_RATE_LIMITED"
                is AiUpstreamAuthenticationException, is AiUpstreamException -> "AI_PROVIDER_ERROR"
                else -> "AI_PROVIDER_ERROR"
            }
            fail(taskId, result, code, if (code == "AI_RATE_LIMITED") "模型服务请求过于频繁，请稍后重试" else "模型服务暂时不可用，请稍后重试", started, requestId, startedAt, rendered)
        } catch (exception: TemplateSchemaValidator.SchemaViolationException) {
            fail(taskId, result, "INVALID_OUTPUT_SCHEMA", "模型返回结果不符合输出契约", started, requestId, startedAt, rendered)
        } catch (exception: Exception) {
            fail(taskId, result, if (exception.message?.contains("剧本内容已变化") == true) "TEMPLATE_SOURCE_CHANGED" else "CHARACTER_ENRICHMENT_FAILED", exception.message?.take(2000) ?: "角色扩写失败", started, requestId, startedAt, rendered)
        }
    }

    private fun fail(taskId: Long, result: CharacterEnrichmentResult, code: String, message: String, task: ProductionTask, requestId: String, startedAt: Long, rendered: TemplateRenderer.RenderedTemplate?) {
        result.resultStatus = CharacterEnrichmentResultStatus.FAILED
        result.errorCode = code
        result.errorMessage = message.take(2000)
        resultRepository.save(result)
        taskCenterService.markFailed(taskId, code, message)
        runCatching {
            templateRunAuditService.record(
                TemplateRunRecord(
                    templateKey = "character_enrichment",
                    templateVersionId = result.promptVersionId,
                    taskId = taskId,
                    invocationId = requestId,
                    providerConfigId = task.providerConfigId,
                    model = task.modelName.orEmpty(),
                    status = "FAILED",
                    attempt = task.attempt,
                    latencyMs = (System.nanoTime() - startedAt) / 1_000_000,
                    inputTokens = rendered?.inputTokens,
                    templateHash = rendered?.templateHash,
                    variablesHash = rendered?.variablesHash,
                    renderedInputHash = rendered?.renderedInputHash,
                    validationErrorCode = code,
                ),
            )
        }
    }

    private fun parseRoot(raw: String): JsonNode {
        val normalized = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (normalized.isBlank()) throw IllegalArgumentException("模型返回结果为空")
        return objectMapper.readTree(normalized) ?: throw IllegalArgumentException("模型返回结果为空")
    }

    private fun contentHashOf(content: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverProcessingTasks() {
        taskRepository.findAllByTaskTypeAndStatusIn(ProductionTaskType.CHARACTER_ENRICHMENT, listOf(ProductionTaskStatus.PENDING, ProductionTaskStatus.QUEUED, ProductionTaskStatus.RUNNING)).forEach { task ->
            val taskId = task.id ?: return@forEach
            val result = resultRepository.findByTaskId(taskId) ?: return@forEach
            if (result.resultStatus != CharacterEnrichmentResultStatus.PROCESSING) return@forEach
            if (task.status == ProductionTaskStatus.RUNNING) {
                task.status = ProductionTaskStatus.QUEUED
                taskRepository.save(task)
            }
            runCatching { executor.execute { execute(taskId) } }.onFailure { taskCenterService.markFailed(taskId, "TASK_QUEUE_FULL", "角色扩写任务队列已满，请稍后重试") }
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
