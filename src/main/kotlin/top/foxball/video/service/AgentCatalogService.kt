package top.foxball.video.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import top.foxball.video.entity.jdbc.AiAgent
import top.foxball.video.entity.jdbc.AiAgentVersion
import top.foxball.video.entity.jdbc.AgentCategory
import top.foxball.video.entity.jdbc.AgentScopeType
import top.foxball.video.entity.jdbc.AgentVersionStatus
import top.foxball.video.entity.jdbc.ProductionStage
import top.foxball.video.repository.AiAgentRepository
import top.foxball.video.repository.AiAgentVersionRepository

/** 智能体目录服务；业务代码只依赖稳定的 agent_key。 */
@Service
class AgentCatalogService(
    private val agentRepository: AiAgentRepository,
    private val versionRepository: AiAgentVersionRepository,
    private val templateCatalogService: TemplateCatalogService,
    private val objectMapper: ObjectMapper,
    private val schemaValidator: TemplateSchemaValidator,
) {
    data class ResolvedAgent(
        val agent: AiAgent,
        val version: AiAgentVersion,
        val template: TemplateCatalogService.ResolvedTemplate,
        val skillKeys: List<String>,
        val mcpBindings: List<McpBinding>,
        val builtinTools: List<String>,
    )

    data class McpBinding(val key: String, val tools: List<String>)

    @Transactional(readOnly = true)
    fun resolveAgent(agentKey: String, userId: Long? = null, projectId: Long? = null): ResolvedAgent {
        val agent = findAgent(agentKey, userId, projectId)
            ?: throw IllegalArgumentException("智能体不存在：${agentKey.trim()}")
        val version = versionRepository.findByAgentIdAndStatusOrderByVersionNoDesc(agent.id!!, AgentVersionStatus.PUBLISHED)
            .firstOrNull() ?: throw IllegalStateException("智能体 ${agent.agentKey} 没有已发布版本")
        return resolveVersion(agent, version)
    }

    @Transactional(readOnly = true)
    fun resolveAgent(agentKey: String, versionNo: Int, userId: Long? = null, projectId: Long? = null): ResolvedAgent {
        val agent = findAgent(agentKey, userId, projectId)
            ?: throw IllegalArgumentException("智能体不存在：${agentKey.trim()}")
        val version = versionRepository.findByAgentIdAndVersionNo(agent.id!!, versionNo)
            ?: throw IllegalArgumentException("智能体版本不存在：${agent.agentKey} v$versionNo")
        return resolveVersion(agent, version)
    }

    @Transactional(readOnly = true)
    fun listAgents(category: AgentCategory? = null, scopeType: AgentScopeType? = null, projectId: Long? = null): List<AiAgent> {
        val candidates = when {
            projectId != null && scopeType != null -> agentRepository.findAllByScopeTypeAndProjectIdAndEnabledTrueOrderByAgentKeyAsc(scopeType, projectId)
            scopeType != null -> agentRepository.findAllByScopeTypeAndEnabledTrueOrderByAgentKeyAsc(scopeType)
            else -> agentRepository.findAllByEnabledTrueOrderByAgentKeyAsc()
        }
        return candidates.filter { category == null || it.category == category }
    }

    @Transactional(readOnly = true)
    fun listVersions(agentKey: String, userId: Long? = null, projectId: Long? = null): List<AiAgentVersion> {
        val agent = findAgent(agentKey, userId, projectId) ?: return emptyList()
        return versionRepository.findByAgentIdOrderByVersionNoDesc(agent.id!!)
    }

    @Transactional(readOnly = true)
    fun listVersions(agentId: Long): List<AiAgentVersion> = versionRepository.findByAgentIdOrderByVersionNoDesc(agentId)

    @Transactional(readOnly = true)
    fun listStageVersions(stage: ProductionStage): List<AiAgentVersion> =
        versionRepository.findByProductionStageAndStatusOrderByStageOrderAsc(stage, AgentVersionStatus.PUBLISHED)

    @Transactional
    fun saveAgent(
        id: Long?,
        agentKey: String,
        displayName: String,
        purpose: String,
        category: AgentCategory,
        scopeType: AgentScopeType,
        projectId: Long?,
        ownerUserId: Long?,
        enabled: Boolean,
        userId: Long?,
    ): AiAgent {
        val key = normalizeKey(agentKey)
        validateScope(scopeType, projectId, ownerUserId)
        val agent = id?.let { agentRepository.findById(it).orElseThrow { IllegalArgumentException("智能体不存在：$it") } }
            ?: run {
                require(findAgentInScope(key, scopeType, projectId, ownerUserId) == null) { "相同作用域的智能体已存在" }
                AiAgent(agentKey = key, createdBy = userId)
            }
        agent.agentKey = key
        agent.displayName = displayName.trim().take(200)
        agent.purpose = purpose.trim().take(1000)
        agent.category = category
        agent.scopeType = scopeType
        agent.projectId = projectId
        agent.ownerUserId = ownerUserId
        agent.enabled = enabled
        return agentRepository.save(agent)
    }

    @Transactional
    fun saveVersion(
        agentKey: String,
        versionNo: Int?,
        inputSchemaJson: String,
        outputSchemaJson: String,
        templateKey: String,
        templateVersionId: Long,
        additionalSkillsJson: String,
        additionalMcpServersJson: String,
        inputMappingJson: String,
        outputMappingJson: String,
        executionConfigJson: String,
        productionStage: ProductionStage?,
        stageOrder: Int,
        taskType: String?,
        changeSummary: String,
        userId: Long?,
    ): AiAgentVersion {
        val agent = agentRepository.findAllByAgentKeyOrderByIdAsc(agentKey.trim()).firstOrNull()
            ?: throw IllegalArgumentException("智能体不存在：$agentKey")
        val template = templateCatalogService.resolveExecutableVersion(templateVersionId)
            ?: throw IllegalArgumentException("模板版本不存在：$templateVersionId")
        require(template.version.status.name == "PUBLISHED") { "智能体只能引用已发布的模板版本" }
        require(template.template.key == templateKey.trim()) { "模板 key 与模板版本不匹配" }
        validateAgentSchemas(inputSchemaJson, outputSchemaJson, inputMappingJson, outputMappingJson, executionConfigJson, additionalSkillsJson, additionalMcpServersJson)
        val nextVersionNo = versionNo ?: ((versionRepository.findByAgentIdOrderByVersionNoDesc(agent.id!!).firstOrNull()?.versionNo ?: 0) + 1)
        val existing = versionNo?.let { versionRepository.findByAgentIdAndVersionNo(agent.id!!, it) }
        require(existing == null || existing.status == AgentVersionStatus.DRAFT) { "只有草稿版本可以编辑" }
        val version = existing ?: AiAgentVersion(agentId = agent.id!!, versionNo = nextVersionNo)
        version.inputSchemaJson = inputSchemaJson
        version.outputSchemaJson = outputSchemaJson
        version.templateKey = templateKey.trim()
        version.templateVersionId = templateVersionId
        version.additionalSkillsJson = additionalSkillsJson
        version.additionalMcpServersJson = additionalMcpServersJson
        version.inputMappingJson = inputMappingJson
        version.outputMappingJson = outputMappingJson
        version.executionConfigJson = executionConfigJson
        version.productionStage = productionStage
        version.stageOrder = stageOrder
        version.taskType = taskType?.trim()?.takeIf { it.isNotEmpty() }
        version.changeSummary = changeSummary.trim().take(1000)
        version.contentHash = contentHash(version)
        version.createdBy = version.createdBy ?: userId
        return versionRepository.save(version)
    }

    @Transactional
    fun submitReview(agentKey: String, versionNo: Int, userId: Long?): AiAgentVersion = transition(agentKey, versionNo, AgentVersionStatus.IN_REVIEW, userId)

    @Transactional
    fun publishVersion(agentKey: String, versionNo: Int, userId: Long?): AiAgentVersion {
        val version = versionRepository.findWithLockById(requireVersionId(agentKey, versionNo))
            ?: throw IllegalArgumentException("智能体版本不存在：$agentKey v$versionNo")
        require(version.status == AgentVersionStatus.IN_REVIEW || version.status == AgentVersionStatus.DRAFT) { "只有草稿或评审中的版本可以发布" }
        require(templateCatalogService.resolveExecutableVersion(version.templateVersionId)?.version?.status?.name == "PUBLISHED") { "引用的模板版本未发布" }
        version.status = AgentVersionStatus.PUBLISHED
        version.publishedBy = userId
        version.publishedAt = LocalDateTime.now()
        version.contentHash = contentHash(version)
        return versionRepository.save(version)
    }

    @Transactional
    fun archiveVersion(agentKey: String, versionNo: Int, userId: Long?): AiAgentVersion {
        val version = versionRepository.findWithLockById(requireVersionId(agentKey, versionNo))
            ?: throw IllegalArgumentException("智能体版本不存在：$agentKey v$versionNo")
        require(version.status == AgentVersionStatus.PUBLISHED) { "只有已发布版本可以归档" }
        version.status = AgentVersionStatus.ARCHIVED
        version.reviewedBy = version.reviewedBy ?: userId
        version.reviewedAt = version.reviewedAt ?: LocalDateTime.now()
        return versionRepository.save(version)
    }

    private fun resolveVersion(agent: AiAgent, version: AiAgentVersion): ResolvedAgent {
        require(version.status == AgentVersionStatus.PUBLISHED || version.status == AgentVersionStatus.ARCHIVED) { "智能体版本尚未发布" }
        val template = templateCatalogService.resolveExecutableVersion(version.templateVersionId)
            ?: throw IllegalStateException("模板版本不存在：${version.templateVersionId}")
        require(template.template.key == version.templateKey) { "智能体模板引用不一致" }
        val templateSkills = schemaValidator.skillKeys(template.version.skillsJson)
        val additionalSkills = parseStringOrBindingArray(version.additionalSkillsJson)
        val templateMcp = schemaValidator.mcpServerBindings(template.version.mcpServersJson).map { McpBinding(it.first, it.second) }
        val additionalMcp = schemaValidator.mcpServerBindings(version.additionalMcpServersJson).map { McpBinding(it.first, it.second) }
        val tools = schemaValidator.toolNames(template.version.toolsJson).distinct()
        return ResolvedAgent(agent, version, template, (templateSkills + additionalSkills).distinct(), mergeMcp(templateMcp, additionalMcp), tools)
    }

    private fun findAgent(agentKey: String, userId: Long?, projectId: Long?): AiAgent? {
        val key = normalizeKey(agentKey)
        if (userId != null) agentRepository.findByAgentKeyAndScopeTypeAndOwnerUserId(key, AgentScopeType.USER, userId)?.takeIf { it.enabled }?.let { return it }
        if (projectId != null) agentRepository.findByAgentKeyAndScopeTypeAndProjectId(key, AgentScopeType.PROJECT, projectId)?.takeIf { it.enabled }?.let { return it }
        return agentRepository.findAllByAgentKeyOrderByIdAsc(key).firstOrNull { it.scopeType == AgentScopeType.GLOBAL && it.enabled }
    }

    private fun findAgentInScope(key: String, scope: AgentScopeType, projectId: Long?, ownerUserId: Long?): AiAgent? = when (scope) {
        AgentScopeType.GLOBAL -> agentRepository.findAllByAgentKeyOrderByIdAsc(key).firstOrNull { it.scopeType == scope }
        AgentScopeType.PROJECT -> projectId?.let { agentRepository.findByAgentKeyAndScopeTypeAndProjectId(key, scope, it) }
        AgentScopeType.USER -> ownerUserId?.let { agentRepository.findByAgentKeyAndScopeTypeAndOwnerUserId(key, scope, it) }
    }

    private fun validateScope(scope: AgentScopeType, projectId: Long?, ownerUserId: Long?) {
        require((scope == AgentScopeType.PROJECT) == (projectId != null)) { "项目作用域必须提供 project_id" }
        require((scope == AgentScopeType.USER) == (ownerUserId != null)) { "个人作用域必须提供 owner_user_id" }
        require(scope == AgentScopeType.GLOBAL || projectId == null || projectId > 0) { "project_id 不合法" }
        require(scope == AgentScopeType.GLOBAL || ownerUserId == null || ownerUserId > 0) { "owner_user_id 不合法" }
    }

    private fun validateAgentSchemas(
        inputSchemaJson: String,
        outputSchemaJson: String,
        inputMappingJson: String,
        outputMappingJson: String,
        executionConfigJson: String,
        additionalSkillsJson: String,
        additionalMcpServersJson: String,
    ) {
        schemaValidator.validateVariablesSchema(inputSchemaJson, "{\"properties\":{}}")
        schemaValidator.validateOutputSchema(outputSchemaJson)
        require(objectMapper.readTree(inputMappingJson).isObject) { "input_mapping_json 必须是对象" }
        require(objectMapper.readTree(outputMappingJson).isObject) { "output_mapping_json 必须是对象" }
        require(objectMapper.readTree(executionConfigJson).isObject) { "execution_config_json 必须是对象" }
        parseStringOrBindingArray(additionalSkillsJson)
        schemaValidator.validateMcpServers(normalizeMcpJson(additionalMcpServersJson))
    }

    private fun transition(agentKey: String, versionNo: Int, target: AgentVersionStatus, userId: Long?): AiAgentVersion {
        val version = versionRepository.findWithLockById(requireVersionId(agentKey, versionNo))
            ?: throw IllegalArgumentException("智能体版本不存在：$agentKey v$versionNo")
        require(version.status == AgentVersionStatus.DRAFT) { "只有草稿版本可以提交评审" }
        version.status = target
        version.reviewedBy = userId
        version.reviewedAt = LocalDateTime.now()
        return versionRepository.save(version)
    }

    private fun requireVersionId(agentKey: String, versionNo: Int): Long {
        val agent = agentRepository.findAllByAgentKeyOrderByIdAsc(normalizeKey(agentKey)).firstOrNull()
            ?: throw IllegalArgumentException("智能体不存在：$agentKey")
        return versionRepository.findByAgentIdAndVersionNo(agent.id!!, versionNo)?.id
            ?: throw IllegalArgumentException("智能体版本不存在：$agentKey v$versionNo")
    }

    private fun parseStringOrBindingArray(json: String): List<String> {
        val node = objectMapper.readTree(json)
        require(node.isArray) { "技能绑定必须是数组" }
        return node.mapNotNull { if (it.isTextual) it.stringValue() else it.path("key").stringValue(null) }
            .filter { !it.isNullOrBlank() }
    }

    private fun normalizeMcpJson(json: String): String {
        val node = objectMapper.readTree(json)
        if (!node.isArray) return "[]"
        val normalized = objectMapper.createArrayNode()
        node.forEach { item ->
            val binding = objectMapper.createObjectNode()
            binding.put("key", item.path("key").stringValue(null).orEmpty())
            item.path("tools").takeIf { it.isArray }?.let { tools -> binding.set("tools", tools) }
            normalized.add(binding)
        }
        return normalized.toString()
    }

    private fun mergeMcp(base: List<McpBinding>, extra: List<McpBinding>): List<McpBinding> {
        val result = linkedMapOf<String, LinkedHashSet<String>>()
        (base + extra).forEach { binding -> result.getOrPut(binding.key) { linkedSetOf() }.addAll(binding.tools) }
        return result.map { McpBinding(it.key, it.value.toList()) }
    }

    private fun contentHash(version: AiAgentVersion): String {
        val canonical = listOf(
            version.inputSchemaJson,
            version.outputSchemaJson,
            version.templateKey,
            version.templateVersionId,
            version.additionalSkillsJson,
            version.additionalMcpServersJson,
            version.inputMappingJson,
            version.outputMappingJson,
            version.executionConfigJson,
            version.productionStage,
            version.stageOrder,
            version.taskType,
        ).joinToString("|")
        return "sha256:" + MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun normalizeKey(key: String): String = key.trim().lowercase().also {
        require(it.matches(Regex("[a-z][a-z0-9_]{1,99}"))) { "agent_key 格式不正确" }
    }
}
