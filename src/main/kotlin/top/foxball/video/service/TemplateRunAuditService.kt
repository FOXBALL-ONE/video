package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.AiTemplateRun
import top.foxball.video.repository.AiTemplateRunRepository

/** 模板运行审计写入参数；只包含版本、哈希、技能与统计信息，不含提示词正文或业务数据。 */
data class TemplateRunRecord(
    val templateKey: String,
    val templateVersionId: Long,
    val taskId: Long?,
    val invocationId: String,
    val providerConfigId: Long?,
    val model: String,
    val status: String,
    val attempt: Int = 1,
    val latencyMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val templateHash: String? = null,
    val variablesHash: String? = null,
    val renderedInputHash: String? = null,
    val skillKeys: List<String> = emptyList(),
    val mcpServerKeys: List<String> = emptyList(),
    val toolCallCount: Int = 0,
    val validationErrorCode: String? = null,
)

/** 写入脱敏的模板运行审计，不接受完整提示词或业务正文参数。 */
@Service
class TemplateRunAuditService(
    private val repository: AiTemplateRunRepository,
    private val objectMapper: tools.jackson.databind.ObjectMapper,
) {
    @Transactional
    fun record(record: TemplateRunRecord): AiTemplateRun = repository.save(
        AiTemplateRun(
            templateKey = record.templateKey.take(100),
            templateVersionId = record.templateVersionId,
            taskId = record.taskId,
            invocationId = record.invocationId.take(100),
            providerConfigId = record.providerConfigId,
            modelName = record.model.take(200),
            status = record.status.take(30),
            attempt = record.attempt.coerceAtLeast(1),
            latencyMs = record.latencyMs,
            inputTokens = record.inputTokens,
            outputTokens = record.outputTokens,
            templateHash = record.templateHash,
            variablesHash = record.variablesHash,
            renderedInputHash = record.renderedInputHash,
            skillsJson = objectMapper.writeValueAsString(record.skillKeys),
            mcpServersJson = objectMapper.writeValueAsString(record.mcpServerKeys),
            toolCallCount = record.toolCallCount,
            validationErrorCode = record.validationErrorCode,
        ),
    )
}
