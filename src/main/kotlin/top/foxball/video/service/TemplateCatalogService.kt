package top.foxball.video.service

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.AiTemplate
import top.foxball.video.entity.jdbc.AiTemplateVersion
import top.foxball.video.entity.jdbc.EpisodeAssetType
import top.foxball.video.entity.jdbc.ProjectMemberRole
import top.foxball.video.entity.jdbc.TemplateCategory
import top.foxball.video.entity.jdbc.TemplateScopeType
import top.foxball.video.entity.jdbc.TemplateVersionStatus
import top.foxball.video.repository.AiTemplateRepository
import top.foxball.video.repository.AiTemplateVersionRepository
import top.foxball.video.repository.ProjectMemberRepository
import top.foxball.video.repository.ProjectRepository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

/**
 * 模板目录、版本生命周期和作用域解析服务。
 *
 * 模板是提示词、技能（SKILL）、MCP 工具与输出契约的统一载体：业务代码只依赖稳定的
 * 模板 key，运行时解析到一个已发布版本，并在创建任务时固定版本与内容哈希。
 */
@Service
class TemplateCatalogService(
    private val templateRepository: AiTemplateRepository,
    private val versionRepository: AiTemplateVersionRepository,
    private val projectRepository: ProjectRepository,
    private val memberRepository: ProjectMemberRepository,
    private val objectMapper: ObjectMapper,
    private val schemaValidator: TemplateSchemaValidator,
    private val skillRegistry: SkillRegistry,
    private val mcpServerRegistry: McpServerRegistry,
    private val toolRegistry: TemplateToolRegistry,
) {
    data class ResolvedTemplate(val template: AiTemplate, val version: AiTemplateVersion)

    class NotFoundException(message: String = "模板不存在") : IllegalArgumentException(message)
    class ConflictException(message: String) : IllegalStateException(message)

    companion object {
        /** 允许被业务代码引用的模板 key；新增 key 必须同时提供内置种子。 */
        val REGISTERED_KEYS = setOf(
            "script_analysis",
            "script_character_extraction",
            "script_scene_extraction",
            "script_prop_extraction",
            "character_enrichment",
            "character_expression_image_prompt",
            "character_three_view_merge",
            "shot_storyboard_grid",
            "asset_list_assistant",
        )

        /**
         * 模板 key 与业务分类的对应关系。
         *
         * 现有库的 `ai_templates.category` 带有 CHECK 约束，只允许这里的取值；`ddl-auto=update` 不会
         * 更新已存在的约束，因此新增分类需要同时改约束（或补迁移脚本），否则应用无法启动。
         * 新增 key 时优先复用已有分类；确实需要新分类时必须先同步数据库约束。
         */
        val CATEGORY_BY_KEY = mapOf(
            "script_analysis" to TemplateCategory.SCRIPT_GENERAL,
            "script_character_extraction" to TemplateCategory.SCRIPT_CHARACTER,
            "script_scene_extraction" to TemplateCategory.SCRIPT_SCENE,
            "script_prop_extraction" to TemplateCategory.SCRIPT_PROP,
            "character_enrichment" to TemplateCategory.CHARACTER_ENRICHMENT,
            "character_expression_image_prompt" to TemplateCategory.CHARACTER_EXPRESSION_IMAGE,
            "character_three_view_merge" to TemplateCategory.CHARACTER_ENRICHMENT,
            "shot_storyboard_grid" to TemplateCategory.STORYBOARD_IMAGE,
            "asset_list_assistant" to TemplateCategory.SCRIPT_GENERAL,
        )
    }

    @Transactional(readOnly = true)
    fun list(
        userId: Long?,
        key: String?,
        scopeType: TemplateScopeType?,
        projectId: Long?,
        status: TemplateVersionStatus?,
        category: TemplateCategory? = null,
    ): List<AiTemplate> {
        val normalizedKey = key?.trim()?.takeIf { it.isNotEmpty() }
        val templates = templateRepository.findAllByOrderByUpdatedAtDesc()
            .asSequence()
            .filter { normalizedKey == null || it.key == normalizedKey }
            .filter { scopeType == null || it.scopeType == scopeType }
            .filter { projectId == null || it.projectId == projectId }
            .filter { category == null || effectiveCategory(it) == category }
            .filter { canReadMetadata(it, userId) }
            .toList()
        if (status == null || templates.isEmpty()) return templates
        val versionsByTemplateId = versionsByTemplateIds(templates.mapNotNull { it.id })
        return templates.filter { template ->
            versionsByTemplateId[template.id].orEmpty().any { version -> version.status == status }
        }
    }

    /** 返回剧本资产提取弹窗可选择的已发布模板版本。 */
    @Transactional(readOnly = true)
    fun scriptAssetTemplateOptions(
        assetType: EpisodeAssetType,
        projectId: Long?,
        userId: Long?,
        locale: String = "zh-CN",
    ): List<ResolvedTemplate> {
        require(projectId == null || isAdmin() || (userId != null && canReadProject(projectId, userId))) { "无权读取项目模板" }
        val category = categoryForAssetType(assetType)
        val requestedLocale = normalizeLocale(locale)
        val personalProject = projectId == null || isPersonalProject(projectId)
        val templates = templateRepository.findAllByOrderByUpdatedAtDesc()
            .asSequence()
            .filter { it.enabled && effectiveCategory(it) == category }
            .filter { it.scopeType == TemplateScopeType.GLOBAL || canRead(it, userId) }
            .filter { scopeRank(it, projectId, userId, personalProject) >= 0 }
            .sortedWith(
                compareByDescending<AiTemplate> { scopeRank(it, projectId, userId, personalProject) }
                    .thenByDescending { it.categoryDefault == true }
                    .thenByDescending { it.updatedAt },
            )
            .toList()
        val versionsByTemplateId = versionsByTemplateIds(templates.mapNotNull { it.id })
        return templates.mapNotNull { template ->
            val versions = versionsByTemplateId[template.id].orEmpty().filter { it.status == TemplateVersionStatus.PUBLISHED }
            val requestedMatches = versions.filter { it.locale.equals(requestedLocale, true) }
            val fallbackMatches = versions.filter { it.locale.equals(template.defaultLocale, true) }
            require(requestedMatches.size <= 1) { "模板存在多个已发布版本：${template.key}/$requestedLocale" }
            require(fallbackMatches.size <= 1) { "模板存在多个默认语言发布版本：${template.key}/${template.defaultLocale}" }
            val version = requestedMatches.firstOrNull() ?: fallbackMatches.firstOrNull()
            version?.let { ResolvedTemplate(template, it) }
        }
    }

    /** 资产类型对应的模板分类。 */
    fun categoryForAssetType(assetType: EpisodeAssetType): TemplateCategory = when (assetType) {
        EpisodeAssetType.CHARACTER -> TemplateCategory.SCRIPT_CHARACTER
        EpisodeAssetType.SCENE_BACKGROUND -> TemplateCategory.SCRIPT_SCENE
        EpisodeAssetType.PROP -> TemplateCategory.SCRIPT_PROP
        else -> throw IllegalArgumentException("该资产类型不支持剧本解析")
    }

    /** 模板 key 决定分类；数据库中的分类列只作为旧数据的兜底。 */
    fun effectiveCategory(template: AiTemplate): TemplateCategory =
        CATEGORY_BY_KEY[template.key] ?: template.category ?: TemplateCategory.SCRIPT_GENERAL

    @Transactional(readOnly = true)
    fun getVersion(templateId: Long, versionId: Long, userId: Long?): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val version = versionRepository.findById(versionId).orElse(null) ?: return null
        if (version.templateId != templateId || !canReadTemplate(template, userId)) return null
        return ResolvedTemplate(template, version)
    }

    @Transactional(readOnly = true)
    fun versions(templateId: Long): List<AiTemplateVersion> =
        versionRepository.findAllByTemplateIdOrderByVersionNoDesc(templateId)

    @Transactional(readOnly = true)
    fun versionsByTemplateIds(templateIds: Collection<Long>): Map<Long, List<AiTemplateVersion>> {
        val ids = templateIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return versionRepository.findAllByTemplateIdInOrderByTemplateIdAscVersionNoDesc(ids)
            .groupBy { it.templateId }
    }

    @Transactional(readOnly = true)
    fun listVersions(templateId: Long, userId: Long?): List<AiTemplateVersion>? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        if (!canReadMetadata(template, userId)) return null
        return versionRepository.findAllByTemplateIdOrderByVersionNoDesc(templateId)
    }

    @Transactional
    fun createDraft(
        userId: Long?,
        key: String,
        displayName: String,
        purpose: String,
        scopeType: TemplateScopeType,
        projectId: Long?,
        locale: String,
        messagesJson: String,
        variablesSchemaJson: String,
        outputSchemaJson: String,
        generationPolicyJson: String,
        compatibilityJson: String,
        skillsJson: String,
        mcpServersJson: String,
        toolsJson: String,
        changeSummary: String,
        category: TemplateCategory = CATEGORY_BY_KEY[normalizeKey(key)] ?: TemplateCategory.SCRIPT_GENERAL,
    ): ResolvedTemplate {
        val normalizedKey = normalizeKey(key)
        require(normalizedKey in REGISTERED_KEYS) { "未注册的模板 key" }
        require(CATEGORY_BY_KEY[normalizedKey] == category) { "模板分类与 key 不匹配" }
        val normalizedLocale = normalizeLocale(locale)
        validateScope(userId, scopeType, projectId, null)
        val ownerUserId = if (scopeType == TemplateScopeType.USER) userId else null
        val duplicate = templateRepository.findAllByKeyOrderByIdAsc(normalizedKey).firstOrNull {
            it.scopeType == scopeType && it.projectId == projectId && it.ownerUserId == ownerUserId
        }
        require(duplicate == null) { "相同作用域的模板定义已存在，请创建新版本" }
        val template = templateRepository.saveAndFlush(
            AiTemplate(
                key = normalizedKey,
                displayName = displayName.trim().take(200),
                purpose = purpose.trim().take(1000),
                category = category,
                scopeType = scopeType,
                projectId = projectId,
                ownerUserId = ownerUserId,
                defaultLocale = normalizedLocale,
                createdBy = userId,
            ),
        )
        val version = saveDraft(
            template = template,
            userId = userId,
            versionNo = 1,
            locale = normalizedLocale,
            messagesJson = messagesJson,
            variablesSchemaJson = variablesSchemaJson,
            outputSchemaJson = outputSchemaJson,
            generationPolicyJson = generationPolicyJson,
            compatibilityJson = compatibilityJson,
            skillsJson = skillsJson,
            mcpServersJson = mcpServersJson,
            toolsJson = toolsJson,
            changeSummary = changeSummary,
        )
        return ResolvedTemplate(template, version)
    }

    @Transactional
    fun updateDraft(
        userId: Long?,
        templateId: Long,
        versionId: Long,
        locale: String,
        messagesJson: String,
        variablesSchemaJson: String,
        outputSchemaJson: String,
        generationPolicyJson: String,
        compatibilityJson: String,
        skillsJson: String,
        mcpServersJson: String,
        toolsJson: String,
        changeSummary: String,
    ): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val version = versionRepository.findWithLockById(versionId) ?: return null
        if (version.templateId != templateId) return null
        require(canWrite(template, userId)) { "无权编辑该模板" }
        if (version.status != TemplateVersionStatus.DRAFT) throw ConflictException("只有草稿版本可以编辑")
        version.locale = normalizeLocale(locale)
        version.messagesJson = messagesJson
        version.variablesSchemaJson = variablesSchemaJson
        version.outputSchemaJson = outputSchemaJson
        version.generationPolicyJson = generationPolicyJson
        version.compatibilityJson = compatibilityJson
        version.skillsJson = skillsJson
        version.mcpServersJson = mcpServersJson
        version.toolsJson = toolsJson
        version.changeSummary = changeSummary.trim().take(1000)
        validateVersion(version)
        version.contentHash = contentHash(version)
        return ResolvedTemplate(template, versionRepository.save(version))
    }

    /** 更新模板定义元数据；内容仍由不可变版本承载。 */
    @Transactional
    fun updateDefinition(
        userId: Long?,
        templateId: Long,
        displayName: String,
        purpose: String,
        defaultLocale: String?,
        enabled: Boolean?,
    ): AiTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        require(canWrite(template, userId)) { "无权编辑该模板" }
        template.displayName = displayName.trim().take(200)
        template.purpose = purpose.trim().take(1000)
        if (!defaultLocale.isNullOrBlank()) template.defaultLocale = normalizeLocale(defaultLocale)
        if (enabled != null) template.enabled = enabled
        return templateRepository.save(template)
    }

    /** 删除模板定义及其全部版本；仍有已发布版本时拒绝删除，避免影响运行中的任务。 */
    @Transactional
    fun deleteDefinition(userId: Long?, templateId: Long): Boolean {
        val template = templateRepository.findById(templateId).orElse(null) ?: return false
        require(canWrite(template, userId)) { "无权删除该模板" }
        val versions = versionRepository.findAllByTemplateIdOrderByVersionNoDesc(templateId)
        if (versions.any { it.status == TemplateVersionStatus.PUBLISHED }) throw ConflictException("存在已发布版本，请先归档后再删除")
        versionRepository.deleteAll(versions)
        templateRepository.delete(template)
        return true
    }

    /** 删除单个草稿版本；已进入评审或发布流程的版本不可删除。 */
    @Transactional
    fun deleteVersion(userId: Long?, templateId: Long, versionId: Long): Boolean {
        val template = templateRepository.findById(templateId).orElse(null) ?: return false
        val version = versionRepository.findWithLockById(versionId) ?: return false
        if (version.templateId != templateId) return false
        require(canWrite(template, userId)) { "无权删除该版本" }
        if (version.status != TemplateVersionStatus.DRAFT) throw ConflictException("只有草稿版本可以删除")
        versionRepository.delete(version)
        return true
    }

    @Transactional
    fun copyVersion(userId: Long?, templateId: Long, sourceVersionId: Long, changeSummary: String): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val source = versionRepository.findById(sourceVersionId).orElse(null) ?: return null
        if (source.templateId != templateId) return null
        require(canWrite(template, userId)) { "无权创建模板版本" }
        val nextVersion = (versionRepository.findAllByTemplateIdOrderByVersionNoDesc(templateId).maxOfOrNull { it.versionNo } ?: 0) + 1
        val version = saveDraft(
            template = template,
            userId = userId,
            versionNo = nextVersion,
            locale = source.locale,
            messagesJson = source.messagesJson,
            variablesSchemaJson = source.variablesSchemaJson,
            outputSchemaJson = source.outputSchemaJson,
            generationPolicyJson = source.generationPolicyJson,
            compatibilityJson = source.compatibilityJson,
            skillsJson = source.skillsJson,
            mcpServersJson = source.mcpServersJson,
            toolsJson = source.toolsJson,
            changeSummary = changeSummary,
        )
        return ResolvedTemplate(template, version)
    }

    @Transactional
    fun submitReview(userId: Long?, templateId: Long, versionId: Long): ResolvedTemplate? =
        transition(userId, templateId, versionId, TemplateVersionStatus.IN_REVIEW)

    @Transactional
    fun rejectReview(userId: Long?, templateId: Long, versionId: Long): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val version = versionRepository.findWithLockById(versionId) ?: return null
        if (version.templateId != templateId) return null
        require(canWrite(template, userId)) { "无权退回该模板" }
        if (version.status != TemplateVersionStatus.IN_REVIEW) throw ConflictException("只有评审中的版本可以退回草稿")
        version.status = TemplateVersionStatus.DRAFT
        version.reviewedBy = userId
        version.reviewedAt = LocalDateTime.now()
        return ResolvedTemplate(template, versionRepository.save(version))
    }

    @Transactional
    fun publish(userId: Long?, templateId: Long, versionId: Long): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val version = versionRepository.findWithLockById(versionId) ?: return null
        if (version.templateId != templateId) return null
        require(canPublish(template, userId)) { "无权发布该模板" }
        if (version.status != TemplateVersionStatus.IN_REVIEW) throw ConflictException("只有评审中的版本可以发布")
        validateVersion(version)
        versionRepository.findAllByTemplateIdOrderByVersionNoDesc(templateId)
            .filter { it.status == TemplateVersionStatus.PUBLISHED && it.locale.equals(version.locale, true) }
            .forEach { current ->
                current.status = TemplateVersionStatus.ARCHIVED
                versionRepository.save(current)
            }
        version.status = TemplateVersionStatus.PUBLISHED
        version.publishedBy = userId
        version.publishedAt = LocalDateTime.now()
        version.reviewedBy = version.reviewedBy ?: userId
        version.reviewedAt = version.reviewedAt ?: LocalDateTime.now()
        return ResolvedTemplate(template, versionRepository.save(version))
    }

    @Transactional
    fun archive(userId: Long?, templateId: Long, versionId: Long): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val version = versionRepository.findWithLockById(versionId) ?: return null
        if (version.templateId != templateId) return null
        require(canPublish(template, userId)) { "无权归档该模板" }
        if (version.status == TemplateVersionStatus.DRAFT) throw ConflictException("草稿无需归档")
        version.status = TemplateVersionStatus.ARCHIVED
        return ResolvedTemplate(template, versionRepository.save(version))
    }

    @Transactional
    fun rollback(userId: Long?, templateId: Long, versionId: Long): ResolvedTemplate? =
        copyVersion(userId, templateId, versionId, "从版本 $versionId 回滚")

    /** 按确定性的作用域和语言规则解析一个可用于新任务的已发布版本。 */
    @Transactional(readOnly = true)
    fun resolve(key: String, projectId: Long?, userId: Long?, locale: String = "zh-CN"): ResolvedTemplate {
        val normalizedKey = normalizeKey(key)
        require(normalizedKey in REGISTERED_KEYS) { "未注册的模板 key" }
        val personalProject = projectId == null || isPersonalProject(projectId)
        val templates = templateRepository.findAllByKeyOrderByIdAsc(normalizedKey)
            .filter { it.enabled }
            .filter { it.scopeType == TemplateScopeType.GLOBAL || canRead(it, userId) }
            .filter { scopeRank(it, projectId, userId, personalProject) >= 0 }
            .sortedByDescending { scopeRank(it, projectId, userId, personalProject) }
        val requestedLocale = normalizeLocale(locale)
        val versionsByTemplateId = versionsByTemplateIds(templates.mapNotNull { it.id })
        templates.forEach { template ->
            val versions = versionsByTemplateId[template.id].orEmpty()
                .filter { it.status == TemplateVersionStatus.PUBLISHED }
            val requestedMatches = versions.filter { it.locale.equals(requestedLocale, true) }
            if (requestedMatches.size > 1) throw ConflictException("模板存在多个已发布版本：$normalizedKey/${template.scopeType}/$requestedLocale")
            val version = requestedMatches.firstOrNull() ?: run {
                val fallbackMatches = versions.filter { it.locale.equals(template.defaultLocale, true) }
                if (fallbackMatches.size > 1) throw ConflictException("模板存在多个默认语言发布版本：$normalizedKey/${template.scopeType}/${template.defaultLocale}")
                fallbackMatches.firstOrNull()
            }
            if (version != null) return ResolvedTemplate(template, version)
        }
        throw NotFoundException("找不到已发布的模板版本：$normalizedKey")
    }

    @Transactional(readOnly = true)
    fun resolveVersion(versionId: Long): ResolvedTemplate? {
        val version = versionRepository.findById(versionId).orElse(null) ?: return null
        val template = templateRepository.findById(version.templateId).orElse(null) ?: return null
        return ResolvedTemplate(template, version)
    }

    @Transactional(readOnly = true)
    fun resolveExecutableVersion(versionId: Long): ResolvedTemplate? {
        val resolved = resolveVersion(versionId) ?: return null
        require(resolved.version.status == TemplateVersionStatus.PUBLISHED || resolved.version.status == TemplateVersionStatus.ARCHIVED) { "模板版本尚未发布" }
        return resolved
    }

    /** 解析剧本资产类型对应的模板，并校验显式版本属于受控的资产模板 key。 */
    @Transactional(readOnly = true)
    fun resolveScriptAssetTemplate(
        assetType: EpisodeAssetType,
        projectId: Long?,
        userId: Long?,
        versionId: Long? = null,
    ): ResolvedTemplate {
        val category = categoryForAssetType(assetType)
        val resolved = versionId?.let { resolveVersion(it) ?: throw NotFoundException("模板版本不存在") }
            ?: scriptAssetTemplateOptions(assetType, projectId, userId, "zh-CN").firstOrNull()
            ?: runCatching { resolve("script_analysis", projectId, userId, "zh-CN") }.getOrElse { throw NotFoundException("找不到已发布的资产模板") }
        // 通用模板 script_analysis 用 asset_type 变量覆盖所有资产类型，因此显式版本与任务快照重试同样允许。
        require(effectiveCategory(resolved.template) == category || resolved.template.key == "script_analysis") { "模板版本与资产类型不匹配" }
        require(resolved.template.enabled) { "模板已停用" }
        require(resolved.version.status == TemplateVersionStatus.PUBLISHED) { "只有已发布模板版本可以用于新任务" }
        require(
            resolved.version.locale.equals("zh-CN", true) ||
                resolved.version.locale.equals(resolved.template.defaultLocale, true),
        ) { "模板版本语言与剧本解析不匹配" }
        require(
            resolved.template.scopeType == TemplateScopeType.GLOBAL ||
                (resolved.template.projectId == projectId && canRead(resolved.template, userId)),
        ) { "无权使用该模板" }
        return resolved
    }

    /** 初始化内置模板；仅供应用启动时使用，不暴露为管理端写接口。 */
    @Transactional
    fun ensureBuiltin(
        key: String,
        displayName: String,
        purpose: String,
        messagesJson: String,
        variablesSchemaJson: String,
        outputSchemaJson: String,
        generationPolicyJson: String,
        skillsJson: String = "[]",
        mcpServersJson: String = "[]",
        toolsJson: String = "[]",
        category: TemplateCategory = CATEGORY_BY_KEY[key] ?: TemplateCategory.SCRIPT_GENERAL,
    ) {
        val existing = templateRepository.findAllByKeyOrderByIdAsc(key).firstOrNull { it.scopeType == TemplateScopeType.GLOBAL }
            ?: templateRepository.saveAndFlush(
                AiTemplate(
                    key = key,
                    displayName = displayName,
                    purpose = purpose,
                    category = category,
                    categoryDefault = category != TemplateCategory.SCRIPT_GENERAL,
                    scopeType = TemplateScopeType.GLOBAL,
                    defaultLocale = "zh-CN",
                    createdBy = null,
                ),
            )
        if (existing.category != category || existing.categoryDefault != (category != TemplateCategory.SCRIPT_GENERAL)) {
            existing.category = category
            existing.categoryDefault = category != TemplateCategory.SCRIPT_GENERAL
            templateRepository.save(existing)
        }
        val versions = versionRepository.findAllByTemplateIdOrderByVersionNoDesc(requireNotNull(existing.id))
        val published = versions.firstOrNull { it.status == TemplateVersionStatus.PUBLISHED && it.locale == "zh-CN" }
        if (published != null && published.contentHash == seededContentHash(messagesJson, variablesSchemaJson, outputSchemaJson, generationPolicyJson, skillsJson, mcpServersJson, toolsJson)) {
            return
        }
        val version = saveDraft(
            template = existing,
            userId = null,
            versionNo = (versions.maxOfOrNull { it.versionNo } ?: 0) + 1,
            locale = "zh-CN",
            messagesJson = messagesJson,
            variablesSchemaJson = variablesSchemaJson,
            outputSchemaJson = outputSchemaJson,
            generationPolicyJson = generationPolicyJson,
            compatibilityJson = "{}",
            skillsJson = skillsJson,
            mcpServersJson = mcpServersJson,
            toolsJson = toolsJson,
            changeSummary = "内置初始版本",
        )
        published?.let { previous ->
            previous.status = TemplateVersionStatus.ARCHIVED
            versionRepository.save(previous)
        }
        version.status = TemplateVersionStatus.PUBLISHED
        version.publishedAt = LocalDateTime.now()
        versionRepository.save(version)
    }

    /** 计算内置模板内容哈希，用于判断既有库中的内置版本是否需要随代码升级。 */
    private fun seededContentHash(
        messagesJson: String,
        variablesSchemaJson: String,
        outputSchemaJson: String,
        generationPolicyJson: String,
        skillsJson: String,
        mcpServersJson: String,
        toolsJson: String,
    ): String = contentHash(
        AiTemplateVersion(
            templateId = 0,
            locale = "zh-CN",
            messagesJson = messagesJson,
            variablesSchemaJson = variablesSchemaJson,
            outputSchemaJson = outputSchemaJson,
            generationPolicyJson = generationPolicyJson,
            compatibilityJson = "{}",
            skillsJson = skillsJson,
            mcpServersJson = mcpServersJson,
            toolsJson = toolsJson,
        ),
    )

    fun canRead(template: AiTemplate, userId: Long?): Boolean {
        if (isAdmin()) return true
        return when (template.scopeType) {
            TemplateScopeType.GLOBAL -> false
            TemplateScopeType.USER -> template.ownerUserId == userId
            TemplateScopeType.PROJECT -> userId != null && template.projectId != null && canReadProject(template.projectId!!, userId)
        }
    }

    /** 元数据可见范围比模板正文更宽；已登录用户可以看到全局模板目录。 */
    fun canReadMetadata(template: AiTemplate, userId: Long?): Boolean =
        template.scopeType == TemplateScopeType.GLOBAL && userId != null || canRead(template, userId)

    private fun canReadTemplate(template: AiTemplate, userId: Long?): Boolean =
        if (isAdmin()) true else when (template.scopeType) {
            TemplateScopeType.GLOBAL -> false
            TemplateScopeType.USER -> template.ownerUserId == userId
            TemplateScopeType.PROJECT -> template.projectId?.let { canManageProject(it, userId) } == true
        }

    fun canWrite(template: AiTemplate, userId: Long?): Boolean {
        if (isAdmin()) return true
        return when (template.scopeType) {
            TemplateScopeType.GLOBAL -> false
            TemplateScopeType.USER -> template.ownerUserId == userId
            TemplateScopeType.PROJECT -> template.projectId?.let { canManageProject(it, userId) } == true
        }
    }

    private fun canPublish(template: AiTemplate, userId: Long?): Boolean = canWrite(template, userId)

    private fun transition(userId: Long?, templateId: Long, versionId: Long, target: TemplateVersionStatus): ResolvedTemplate? {
        val template = templateRepository.findById(templateId).orElse(null) ?: return null
        val version = versionRepository.findWithLockById(versionId) ?: return null
        if (version.templateId != templateId) return null
        require(canWrite(template, userId)) { "无权操作该模板" }
        if (target == TemplateVersionStatus.IN_REVIEW && version.status != TemplateVersionStatus.DRAFT) throw ConflictException("只有草稿版本可以提交评审")
        version.status = target
        if (target == TemplateVersionStatus.IN_REVIEW) {
            validateVersion(version)
            version.reviewedBy = userId
            version.reviewedAt = LocalDateTime.now()
        }
        return ResolvedTemplate(template, versionRepository.save(version))
    }

    private fun saveDraft(
        template: AiTemplate,
        userId: Long?,
        versionNo: Int,
        locale: String,
        messagesJson: String,
        variablesSchemaJson: String,
        outputSchemaJson: String,
        generationPolicyJson: String,
        compatibilityJson: String,
        skillsJson: String,
        mcpServersJson: String,
        toolsJson: String,
        changeSummary: String,
    ): AiTemplateVersion {
        val version = AiTemplateVersion(
            templateId = requireNotNull(template.id),
            versionNo = versionNo,
            locale = normalizeLocale(locale),
            messagesJson = messagesJson,
            variablesSchemaJson = variablesSchemaJson,
            outputSchemaJson = outputSchemaJson,
            generationPolicyJson = generationPolicyJson,
            compatibilityJson = compatibilityJson,
            skillsJson = skillsJson,
            mcpServersJson = mcpServersJson,
            toolsJson = toolsJson,
            changeSummary = changeSummary.trim().take(1000),
            createdBy = userId,
        )
        validateVersion(version)
        version.contentHash = contentHash(version)
        return versionRepository.save(version)
    }

    /**
     * 校验版本内容。除消息、变量、输出与生成策略外，还要求引用的技能、MCP 服务端和
     * 内置工具都已注册，避免把无法执行的模板发布给任务使用。
     */
    private fun validateVersion(version: AiTemplateVersion) {
        schemaValidator.validateMessages(version.messagesJson)
        schemaValidator.validateVariablesSchema(version.variablesSchemaJson, version.messagesJson)
        schemaValidator.validateOutputSchema(version.outputSchemaJson)
        schemaValidator.validateGenerationPolicy(version.generationPolicyJson)
        schemaValidator.validateSkills(version.skillsJson)
        schemaValidator.validateMcpServers(version.mcpServersJson)
        schemaValidator.validateTools(version.toolsJson)
        skillRegistry.requireAvailable(schemaValidator.skillKeys(version.skillsJson))
        mcpServerRegistry.requireAvailable(schemaValidator.mcpServerKeys(version.mcpServersJson))
        toolRegistry.requireAvailable(schemaValidator.toolNames(version.toolsJson))
    }

    /** 内容哈希覆盖全部版本字段，包含技能、MCP 与工具绑定，任务快照据此固定执行内容。 */
    private fun contentHash(version: AiTemplateVersion): String {
        val canonical = listOf(
            version.locale,
            normalizeJson(version.messagesJson),
            normalizeJson(version.variablesSchemaJson),
            normalizeJson(version.outputSchemaJson),
            normalizeJson(version.generationPolicyJson),
            normalizeJson(version.compatibilityJson),
            normalizeJson(version.skillsJson),
            normalizeJson(version.mcpServersJson),
            normalizeJson(version.toolsJson),
        ).joinToString("\n")
        return "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun normalizeJson(json: String): String = runCatching { objectMapper.readTree(json)?.toString() ?: "null" }.getOrElse { json.trim() }

    private fun normalizeKey(key: String): String = key.trim().lowercase().also { require(it.matches(Regex("[a-z][a-z0-9_]{1,99}"))) { "模板 key 格式不正确" } }

    private fun normalizeLocale(locale: String): String = locale.trim().ifBlank { "zh-CN" }.take(20)

    private fun validateScope(userId: Long?, scopeType: TemplateScopeType, projectId: Long?, ownerUserId: Long?) {
        when (scopeType) {
            TemplateScopeType.GLOBAL -> require(isAdmin()) { "只有系统管理员可以维护全局模板" }
            TemplateScopeType.PROJECT -> require(projectId != null && canManageProject(projectId, userId)) { "无权维护项目模板" }
            TemplateScopeType.USER -> require(
                userId != null && projectId != null && ownerUserId == null &&
                    isPersonalProject(projectId) && canReadProject(projectId, userId),
            ) { "个人模板必须绑定当前用户并且只能用于个人项目" }
        }
    }

    private fun scopeRank(template: AiTemplate, projectId: Long?, userId: Long?, personalProject: Boolean): Int = when {
        template.scopeType == TemplateScopeType.USER && personalProject && template.ownerUserId == userId && template.projectId == projectId -> 3
        template.scopeType == TemplateScopeType.PROJECT && template.projectId == projectId -> 2
        template.scopeType == TemplateScopeType.GLOBAL -> 1
        else -> -1
    }

    private fun canReadProject(projectId: Long, userId: Long): Boolean {
        if (!projectRepository.existsById(projectId)) return false
        val members = memberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId)
        return members.isEmpty() || members.any { it.userId == userId }
    }

    private fun canManageProject(projectId: Long, userId: Long?): Boolean {
        if (isAdmin() || userId == null || !projectRepository.existsById(projectId)) return false
        val members = memberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId)
        return members.isEmpty() || members.any { it.userId == userId && it.role == ProjectMemberRole.OWNER }
    }

    private fun isPersonalProject(projectId: Long): Boolean = memberRepository.findAllByProjectIdOrderByJoinedAtAsc(projectId).size <= 1

    private fun isAdmin(): Boolean = SecurityContextHolder.getContext().authentication?.authorities
        ?.any { it.authority == "ROLE_ADMIN" || it.authority == "system:admin" } == true
}
