package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import top.foxball.video.entity.jdbc.Asset
import top.foxball.video.entity.jdbc.AssetIssue
import top.foxball.video.entity.jdbc.AssetStatus
import top.foxball.video.entity.jdbc.AssetType
import top.foxball.video.repository.AssetIssueRepository
import top.foxball.video.repository.AssetRepository
import top.foxball.video.repository.ProjectRepository
import java.time.LocalDateTime

/** 项目资产及其完整性问题服务。 */
@Service
class AssetService(
    private val projectRepository: ProjectRepository,
    private val assetRepository: AssetRepository,
    private val issueRepository: AssetIssueRepository,
) {
    data class Snapshot(val assets: List<Asset>, val issues: List<AssetIssue>)

    @Transactional(readOnly = true)
    fun load(projectId: Long): Snapshot? {
        if (!projectRepository.existsById(projectId)) return null
        return Snapshot(assetRepository.findAllByProjectIdOrderByUpdatedAtDesc(projectId), issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId))
    }

    @Transactional
    fun create(projectId: Long, name: String, type: AssetType, description: String?, ownerId: Long?, status: AssetStatus): Asset? {
        if (!projectRepository.existsById(projectId)) return null
        return assetRepository.save(Asset(projectId = projectId, name = name, type = type, description = description, ownerId = ownerId, status = status))
    }

    @Transactional
    fun update(projectId: Long, assetId: Long, name: String, type: AssetType, description: String?, status: AssetStatus): Asset? {
        val asset = assetRepository.findById(assetId).orElse(null) ?: return null
        if (asset.projectId != projectId) return null
        asset.name = name
        asset.type = type
        asset.description = description
        asset.status = status
        if (status == AssetStatus.NEEDS_COPYRIGHT) asset.copyrightRequired = true
        return assetRepository.save(asset)
    }

    @Transactional
    fun resolveIssues(projectId: Long): Int {
        if (!projectRepository.existsById(projectId)) return 0
        val issues = issueRepository.findAllByProjectIdAndResolvedFalseOrderBySeverityDesc(projectId)
        issues.forEach { it.resolved = true; it.resolvedAt = LocalDateTime.now() }
        issueRepository.saveAll(issues)
        return issues.size
    }
}
