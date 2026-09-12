package top.foxball.video.repository

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import top.foxball.video.entity.jdbc.AiMcpServer
import top.foxball.video.entity.jdbc.AiSkill
import top.foxball.video.entity.jdbc.AiTemplate
import top.foxball.video.entity.jdbc.AiTemplateEvaluation
import top.foxball.video.entity.jdbc.AiTemplateRun
import top.foxball.video.entity.jdbc.AiTemplateVersion

/** 模板定义仓库。 */
interface AiTemplateRepository : JpaRepository<AiTemplate, Long> {
    fun findAllByOrderByUpdatedAtDesc(): List<AiTemplate>

    fun findAllByKeyOrderByIdAsc(key: String): List<AiTemplate>
}

/** 模板版本仓库；草稿覆盖保存前通过悲观锁串行化同一版本的并发编辑。 */
interface AiTemplateVersionRepository : JpaRepository<AiTemplateVersion, Long> {
    fun findAllByTemplateIdOrderByVersionNoDesc(templateId: Long): List<AiTemplateVersion>

    @Query(
        "select version from AiTemplateVersion version where version.templateId in :templateIds " +
            "order by version.templateId asc, version.versionNo desc",
    )
    fun findAllByTemplateIdInOrderByTemplateIdAscVersionNoDesc(
        @Param("templateIds") templateIds: Collection<Long>,
    ): List<AiTemplateVersion>

    fun findByTemplateIdAndVersionNo(templateId: Long, versionNo: Int): AiTemplateVersion?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): AiTemplateVersion?
}

/** 技能仓库。 */
interface AiSkillRepository : JpaRepository<AiSkill, Long> {
    fun findAllByOrderByKeyAsc(): List<AiSkill>

    fun findAllByKeyOrderByIdAsc(key: String): List<AiSkill>
}

/** MCP 服务端仓库。 */
interface AiMcpServerRepository : JpaRepository<AiMcpServer, Long> {
    fun findAllByOrderByKeyAsc(): List<AiMcpServer>

    fun findAllByKeyOrderByIdAsc(key: String): List<AiMcpServer>
}

/** 模板评估仓库；首期只有写入路径。 */
interface AiTemplateEvaluationRepository : JpaRepository<AiTemplateEvaluation, Long> {
    fun findAllByTemplateKeyOrderByCreatedAtDesc(templateKey: String): List<AiTemplateEvaluation>
}

/** 模板运行审计仓库。 */
interface AiTemplateRunRepository : JpaRepository<AiTemplateRun, Long> {
    fun findAllByTemplateVersionIdOrderByCreatedAtDesc(templateVersionId: Long): List<AiTemplateRun>

    fun findAllByTaskIdOrderByCreatedAtAsc(taskId: Long): List<AiTemplateRun>
}
