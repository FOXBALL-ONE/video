package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.AiConversation
import top.foxball.video.entity.jdbc.AiMessage
import top.foxball.video.entity.jdbc.ProjectMember
import top.foxball.video.entity.jdbc.TimelineClip
import top.foxball.video.entity.jdbc.TimelineTrack

/** 项目成员的数据访问接口。 */
interface ProjectMemberRepository : JpaRepository<ProjectMember, Long> {
    /** 查询项目成员，按加入时间排序。 */
    fun findAllByProjectIdOrderByJoinedAtAsc(projectId: Long): List<ProjectMember>

    /** 查询用户在项目中的成员记录。 */
    fun findByProjectIdAndUserId(projectId: Long, userId: Long): ProjectMember?

    /** 判断用户是否已加入项目。 */
    fun existsByProjectIdAndUserId(projectId: Long, userId: Long): Boolean
}

/** AI 助手会话的数据访问接口。 */
interface AiConversationRepository : JpaRepository<AiConversation, Long> {
    /** 查询项目的 AI 会话，最新会话优先。 */
    fun findAllByProjectIdOrderByCreatedAtDesc(projectId: Long): List<AiConversation>
}

/** AI 助手消息的数据访问接口。 */
interface AiMessageRepository : JpaRepository<AiMessage, Long> {
    /** 按发送顺序查询会话消息。 */
    fun findAllByConversationIdOrderByCreatedAtAsc(conversationId: Long): List<AiMessage>
}

/** 项目时间线轨道的数据访问接口。 */
interface TimelineTrackRepository : JpaRepository<TimelineTrack, Long> {
    /** 查询项目轨道，按时间线排序。 */
    fun findAllByProjectIdOrderBySortOrderAsc(projectId: Long): List<TimelineTrack>
}

/** 时间线剪辑片段的数据访问接口。 */
interface TimelineClipRepository : JpaRepository<TimelineClip, Long> {
    /** 查询轨道片段，按时间线顺序排列。 */
    fun findAllByTrackIdOrderBySortOrderAsc(trackId: Long): List<TimelineClip>
}
