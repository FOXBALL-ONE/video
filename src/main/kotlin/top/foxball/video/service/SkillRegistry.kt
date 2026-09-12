package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import top.foxball.video.config.FileProperties
import top.foxball.video.entity.jdbc.AiSkill
import top.foxball.video.entity.jdbc.SkillSource
import top.foxball.video.repository.AiSkillRepository
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * 技能注册表。
 *
 * 技能是一段稳定的方法论说明（导演、分镜师等），被执行器注入为独立的 system 区块。
 * 技能可以声明需要一并装配的内置工具与 MCP 服务端。技能有三个来源：
 * 内置种子（BUILTIN）、文件系统（FILE，`{storage}/skills/<key>/SKILL.md`）与管理接口（DATABASE）。
 */
@Service
class SkillRegistry(
    private val repository: AiSkillRepository,
    private val objectMapper: ObjectMapper,
    private val fileProperties: FileProperties,
    private val toolRegistry: TemplateToolRegistry,
    private val mcpServerRegistry: McpServerRegistry,
) {
    /** 执行期使用的技能快照；不携带数据库标识，避免执行层接触持久化细节。 */
    data class SkillSpec(
        val key: String,
        val displayName: String,
        val description: String,
        val instructions: String,
        val tools: List<String>,
        val mcpServers: List<String>,
        val contentHash: String,
    )

    @Transactional(readOnly = true)
    fun list(): List<AiSkill> = repository.findAllByOrderByKeyAsc()

    @Transactional(readOnly = true)
    fun findById(id: Long): AiSkill? = repository.findById(id).orElse(null)

    @Transactional(readOnly = true)
    fun getSkill(skillKey: String): AiSkill? =
        repository.findAllByKeyOrderByIdAsc(skillKey.trim().lowercase()).firstOrNull { it.enabled }

    /** 校验技能引用；未注册或已停用的技能都会导致模板无法发布。 */
    @Transactional(readOnly = true)
    fun requireAvailable(skillKeys: Collection<String>) {
        if (skillKeys.isEmpty()) return
        val available = repository.findAllByOrderByKeyAsc().filter { it.enabled }.map { it.key }.toSet()
        val missing = skillKeys.filter { it !in available }
        require(missing.isEmpty()) { "未注册或已停用的技能：${missing.joinToString(", ")}" }
    }

    /** 读取执行期技能快照；顺序与引用顺序一致，缺失的技能直接跳过。 */
    @Transactional(readOnly = true)
    fun resolveSpecs(skillKeys: List<String>): List<SkillSpec> {
        if (skillKeys.isEmpty()) return emptyList()
        val skills = repository.findAllByOrderByKeyAsc().filter { it.enabled }.associateBy { it.key }
        return skillKeys.mapNotNull { key -> skills[key]?.let(::toSpec) }
    }

    /** 新增或更新技能；`id` 为空时按 key 新建。 */
    @Transactional
    fun save(
        userId: Long?,
        id: Long?,
        key: String,
        displayName: String,
        description: String,
        instructions: String,
        toolsJson: String,
        mcpServersJson: String,
        enabled: Boolean,
    ): AiSkill {
        val normalizedKey = normalizeKey(key)
        validateInstructions(instructions)
        val tools = readStringArray(toolsJson, "内置工具")
        val mcpServers = readStringArray(mcpServersJson, "MCP 服务端")
        toolRegistry.requireAvailable(tools)
        mcpServerRegistry.requireAvailable(mcpServers)
        val existing = id?.let { repository.findById(it).orElse(null) }
            ?: repository.findAllByKeyOrderByIdAsc(normalizedKey).firstOrNull()
        require(existing == null || existing.source != SkillSource.BUILTIN) { "内置技能请通过模板管理模块维护" }
        val skill = existing ?: AiSkill(key = normalizedKey, source = SkillSource.DATABASE, createdBy = userId)
        skill.key = normalizedKey
        skill.displayName = displayName.trim().take(200)
        skill.description = description.trim().take(1000)
        skill.instructions = instructions.trim()
        skill.toolsJson = writeStringArray(tools)
        skill.mcpServersJson = writeStringArray(mcpServers)
        skill.enabled = enabled
        skill.contentHash = hashOf(skill)
        return repository.save(skill)
    }

    @Transactional
    fun delete(id: Long): Boolean {
        val skill = repository.findById(id).orElse(null) ?: return false
        require(skill.source != SkillSource.BUILTIN) { "内置技能不能删除" }
        repository.delete(skill)
        return true
    }

    /**
     * 建立或刷新内置技能。
     *
     * 内置技能随代码发布，启动时按内容哈希判断是否需要更新；同名但来源不是 BUILTIN 的技能
     * （文件或管理端创建）不会被覆盖。
     */
    @Transactional
    fun ensureBuiltin(
        key: String,
        displayName: String,
        description: String,
        instructions: String,
        tools: List<String> = emptyList(),
        mcpServers: List<String> = emptyList(),
    ) {
        val normalizedKey = normalizeKey(key)
        validateInstructions(instructions)
        toolRegistry.requireAvailable(tools)
        mcpServerRegistry.requireAvailable(mcpServers)
        val existing = repository.findAllByKeyOrderByIdAsc(normalizedKey).firstOrNull()
        if (existing != null && existing.source != SkillSource.BUILTIN) return
        val skill = existing ?: AiSkill(key = normalizedKey, source = SkillSource.BUILTIN)
        skill.displayName = displayName.trim().take(200)
        skill.description = description.trim().take(1000)
        skill.instructions = instructions.trim()
        skill.toolsJson = writeStringArray(tools)
        skill.mcpServersJson = writeStringArray(mcpServers)
        skill.enabled = true
        val contentHash = hashOf(skill)
        if (existing != null && existing.contentHash == contentHash) return
        skill.contentHash = contentHash
        repository.save(skill)
    }

    /**
     * 导入 `{storage}/skills/<key>/SKILL.md` 中的技能定义。
     *
     * 文件技能使用 YAML frontmatter 描述元数据，正文作为技能说明。内置技能与数据库技能不会被覆盖，
     * 只有来源为 FILE 或尚不存在时才会写入，保证管理端的编辑不会被启动流程回滚。
     */
    @Transactional
    fun importFromFileSystem(): Int {
        val root = Path.of(fileProperties.storagePath, SKILLS_DIRECTORY)
        if (!Files.isDirectory(root)) return 0
        var imported = 0
        Files.newDirectoryStream(root).use { entries ->
            entries.filter { Files.isDirectory(it) }.forEach { directory ->
                val file = directory.resolve(SKILL_FILE_NAME)
                if (!Files.isRegularFile(file)) return@forEach
                val parsed = runCatching { parseSkillFile(Files.readString(file, StandardCharsets.UTF_8), file) }.getOrNull()
                    ?: return@forEach
                val existing = repository.findAllByKeyOrderByIdAsc(parsed.key).firstOrNull()
                if (existing != null && existing.source != SkillSource.FILE) return@forEach
                if (existing != null && existing.contentHash == parsed.contentHash) return@forEach
                toolRegistry.requireAvailable(parsed.tools)
                mcpServerRegistry.requireAvailable(parsed.mcpServers)
                val skill = existing ?: AiSkill(key = parsed.key, source = SkillSource.FILE)
                skill.displayName = parsed.displayName.take(200)
                skill.description = parsed.description.take(1000)
                skill.instructions = parsed.instructions
                skill.toolsJson = writeStringArray(parsed.tools)
                skill.mcpServersJson = writeStringArray(parsed.mcpServers)
                skill.storagePath = file.toString().take(500)
                skill.enabled = parsed.enabled
                skill.contentHash = parsed.contentHash
                repository.save(skill)
                imported++
            }
        }
        return imported
    }

    /** 把技能说明拼装成单个 system 区块；没有技能时返回 null。 */
    fun systemBlock(specs: List<SkillSpec>): String? {
        if (specs.isEmpty()) return null
        return specs.joinToString("\n\n") { spec ->
            buildString {
                appendLine("<skill key=\"${spec.key}\" name=\"${spec.displayName}\">")
                appendLine(spec.instructions.trim())
                append("</skill>")
            }
        }
    }

    private fun toSpec(skill: AiSkill): SkillSpec = SkillSpec(
        key = skill.key,
        displayName = skill.displayName,
        description = skill.description,
        instructions = skill.instructions,
        tools = readStringArray(skill.toolsJson, "内置工具"),
        mcpServers = readStringArray(skill.mcpServersJson, "MCP 服务端"),
        contentHash = skill.contentHash,
    )

    /** 技能说明进入 system 通道，禁止变量占位符，避免运行时数据污染指令区。 */
    private fun validateInstructions(instructions: String) {
        val trimmed = instructions.trim()
        require(trimmed.isNotEmpty()) { "技能说明不能为空" }
        require(trimmed.length <= MAX_INSTRUCTIONS_LENGTH) { "技能说明不能超过 $MAX_INSTRUCTIONS_LENGTH 个字符" }
        require(!trimmed.contains("{{")) { "技能说明不得包含模板变量" }
        require(trimmed.none { it.code < 0x20 && it != '\n' && it != '\r' && it != '\t' }) { "技能说明包含不允许的控制字符" }
    }

    private fun parseSkillFile(content: String, file: Path): ParsedSkill {
        val normalized = content.replace("\r\n", "\n")
        val match = FRONT_MATTER_PATTERN.find(normalized)
            ?: throw IllegalArgumentException("技能文件缺少 frontmatter：$file")
        val body = normalized.substring(match.range.last + 1).trim()
        val metadata = match.groupValues[1].lines()
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).trim().lowercase() to line.substring(separator + 1).trim()
            }
            .toMap()
        val key = (metadata["key"] ?: metadata["name"])?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("技能文件缺少 key：$file")
        validateInstructions(body)
        val skill = AiSkill(
            key = normalizeKey(key),
            displayName = metadata["display_name"] ?: metadata["name"] ?: key,
            description = metadata["description"].orEmpty(),
            instructions = body,
            toolsJson = writeStringArray(splitList(metadata["tools"])),
            mcpServersJson = writeStringArray(splitList(metadata["mcp_servers"])),
            enabled = metadata["enabled"]?.toBooleanStrictOrNull() ?: true,
        )
        return ParsedSkill(
            key = skill.key,
            displayName = skill.displayName,
            description = skill.description,
            instructions = skill.instructions,
            tools = splitList(metadata["tools"]),
            mcpServers = splitList(metadata["mcp_servers"]),
            enabled = skill.enabled,
            contentHash = hashOf(skill),
        )
    }

    private fun hashOf(skill: AiSkill): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(
            listOf(skill.key, skill.displayName, skill.description, skill.instructions, skill.toolsJson, skill.mcpServersJson, skill.enabled.toString())
                .joinToString("\n")
                .toByteArray(StandardCharsets.UTF_8),
        )
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun normalizeKey(key: String): String = key.trim().lowercase()
        .also { require(it.matches(Regex("[a-z][a-z0-9_]{1,99}"))) { "技能 key 格式不正确" } }

    private fun splitList(value: String?): List<String> =
        value?.split(',')?.map(String::trim)?.filter(String::isNotBlank).orEmpty()

    private fun readStringArray(json: String, label: String): List<String> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { objectMapper.readTree(json) }.getOrNull() ?: return emptyList()
        require(root.isArray) { "$label 必须是 JSON 数组" }
        return root.mapNotNull { it.stringValue(null)?.takeIf(String::isNotBlank) }
    }

    private fun writeStringArray(values: List<String>): String = objectMapper.writeValueAsString(values)

    private data class ParsedSkill(
        val key: String,
        val displayName: String,
        val description: String,
        val instructions: String,
        val tools: List<String>,
        val mcpServers: List<String>,
        val enabled: Boolean,
        val contentHash: String,
    )

    private companion object {
        const val SKILLS_DIRECTORY = "skills"
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_INSTRUCTIONS_LENGTH = 40_000
        val FRONT_MATTER_PATTERN = Regex("(?s)^---\\n(.*?)\\n---\\n?")
    }
}
