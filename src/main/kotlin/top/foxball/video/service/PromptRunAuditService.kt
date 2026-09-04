package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.PromptRunAudit
import top.foxball.video.repository.PromptRunAuditRepository

/** 写入脱敏的提示词运行审计，不接受完整提示词或业务正文参数。 */
@Service
class PromptRunAuditService(private val repository: PromptRunAuditRepository) {
    @Transactional
    fun record(
        promptKey: String,
        promptVersionId: Long,
        taskId: Long?,
        invocationId: String,
        providerConfigId: Long?,
        model: String,
        status: String,
        attempt: Int,
        latencyMs: Long?,
        inputTokens: Int?,
        outputTokens: Int?,
        templateHash: String?,
        variablesHash: String?,
        renderedInputHash: String?,
        validationErrorCode: String?,
    ): PromptRunAudit = repository.save(
        PromptRunAudit(
            promptKey = promptKey,
            promptVersionId = promptVersionId,
            taskId = taskId,
            invocationId = invocationId,
            providerConfigId = providerConfigId,
            modelName = model.take(200),
            status = status.take(30),
            attempt = attempt.coerceAtLeast(1),
            latencyMs = latencyMs,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            templateHash = templateHash,
            variablesHash = variablesHash,
            renderedInputHash = renderedInputHash,
            validationErrorCode = validationErrorCode,
        ),
    )
}
