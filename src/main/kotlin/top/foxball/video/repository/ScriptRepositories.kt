package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.ScriptCharacter
import top.foxball.video.entity.jdbc.ScriptDialogue
import top.foxball.video.entity.jdbc.ScriptDocument
import top.foxball.video.entity.jdbc.ScriptQualityCheck
import top.foxball.video.entity.jdbc.ScriptScene
import top.foxball.video.entity.jdbc.ScriptVersion

/** 剧本文档的数据访问接口。 */
interface ScriptDocumentRepository : JpaRepository<ScriptDocument, Long> {
    /** 查询项目唯一对应的剧本文档。 */
    fun findByProjectId(projectId: Long): ScriptDocument?
}

/** 剧本版本的数据访问接口。 */
interface ScriptVersionRepository : JpaRepository<ScriptVersion, Long> {
    /** 查询项目的全部剧本版本，版本号倒序排列。 */
    fun findAllByProjectIdOrderByVersionNoDesc(projectId: Long): List<ScriptVersion>

    /** 查询文档的全部剧本版本，版本号倒序排列。 */
    fun findAllByScriptDocumentIdOrderByVersionNoDesc(scriptDocumentId: Long): List<ScriptVersion>

    /** 查询文档指定版本号。 */
    fun findByScriptDocumentIdAndVersionNo(scriptDocumentId: Long, versionNo: Int): ScriptVersion?

    /** 查询文档最新版本。 */
    fun findFirstByScriptDocumentIdOrderByVersionNoDesc(scriptDocumentId: Long): ScriptVersion?
}

/** 剧本场次的数据访问接口。 */
interface ScriptSceneRepository : JpaRepository<ScriptScene, Long> {

    /** 按剧本顺序查询所有场次。 */
    fun findAllByScriptVersionIdOrderBySortOrderAsc(scriptVersionId: Long): List<ScriptScene>

    /** 查询剧本版本中的指定场次。 */
    fun findByScriptVersionIdAndSceneNo(scriptVersionId: Long, sceneNo: Int): ScriptScene?
}

/** 剧本角色的数据访问接口。 */
interface ScriptCharacterRepository : JpaRepository<ScriptCharacter, Long> {
    /** 查询剧本版本的全部角色。 */
    fun findAllByScriptVersionIdOrderByNameAsc(scriptVersionId: Long): List<ScriptCharacter>

    /** 按名称查询剧本版本中的角色。 */
    fun findByScriptVersionIdAndName(scriptVersionId: Long, name: String): ScriptCharacter?
}

/** 剧本对白的数据访问接口。 */
interface ScriptDialogueRepository : JpaRepository<ScriptDialogue, Long> {
    /** 按场次顺序查询对白。 */
    fun findAllByScriptSceneIdOrderBySequenceNoAsc(scriptSceneId: Long): List<ScriptDialogue>

    /** 查询指定角色的全部对白。 */
    fun findAllByCharacterIdOrderByScriptSceneIdAscSequenceNoAsc(characterId: Long): List<ScriptDialogue>
}

/** 剧本质量检查结果的数据访问接口。 */
interface ScriptQualityCheckRepository : JpaRepository<ScriptQualityCheck, Long> {
    /** 查询剧本版本的质量检查结果，最新检查优先。 */
    fun findAllByScriptVersionIdOrderByCreatedAtDesc(scriptVersionId: Long): List<ScriptQualityCheck>

    /** 查询剧本版本未通过的质量规则。 */
    fun findAllByScriptVersionIdAndPassedFalseOrderByCreatedAtDesc(scriptVersionId: Long): List<ScriptQualityCheck>
}
