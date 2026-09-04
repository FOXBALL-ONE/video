package top.foxball.video.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.video.entity.jdbc.PromptDefinition
import top.foxball.video.entity.jdbc.PromptEvaluation
import top.foxball.video.entity.jdbc.PromptRunAudit
import top.foxball.video.entity.jdbc.PromptVersion

/** AI 提示词定义的数据访问接口。 */
interface PromptDefinitionRepository : JpaRepository<PromptDefinition, Long> {
    fun findAllByOrderByUpdatedAtDesc(): List<PromptDefinition>

    fun findAllByKeyOrderByIdAsc(key: String): List<PromptDefinition>
}

/** AI 提示词版本的数据访问接口。 */
interface PromptVersionRepository : JpaRepository<PromptVersion, Long> {
    fun findAllByPromptDefinitionIdOrderByVersionNoDesc(promptDefinitionId: Long): List<PromptVersion>

    @Query(
        """
        select version
        from PromptVersion version
        where version.promptDefinitionId in :promptDefinitionIds
        order by version.promptDefinitionId asc, version.versionNo desc
        """,
    )
    fun findAllByPromptDefinitionIdInOrderByPromptDefinitionIdAscVersionNoDesc(
        @Param("promptDefinitionIds") promptDefinitionIds: Collection<Long>,
    ): List<PromptVersion>

    fun findByPromptDefinitionIdAndVersionNo(promptDefinitionId: Long, versionNo: Int): PromptVersion?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): PromptVersion?
}

/** 提示词评测记录的数据访问接口。 */
interface PromptEvaluationRepository : JpaRepository<PromptEvaluation, Long> {
    fun findAllByPromptKeyOrderByCreatedAtDesc(promptKey: String): List<PromptEvaluation>
}

/** 提示词运行审计的数据访问接口。 */
interface PromptRunAuditRepository : JpaRepository<PromptRunAudit, Long> {
    fun findAllByPromptVersionIdOrderByCreatedAtDesc(promptVersionId: Long): List<PromptRunAudit>

    fun findAllByTaskIdOrderByCreatedAtAsc(taskId: Long): List<PromptRunAudit>
}
