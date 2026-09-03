package top.foxball.video.service

import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 执行器读取的取消令牌。
 *
 * 数据库中的任务状态是最终事实来源；该注册表只用于降低 worker 轮询数据库的压力，
 * 进程重启后由持久化的 CANCEL_REQUESTED 状态重新补发令牌。
 */
@Service
class TaskCancellationRegistry {
    private val requested = ConcurrentHashMap<Long, LocalDateTime>()

    fun request(taskId: Long) {
        requested.putIfAbsent(taskId, LocalDateTime.now())
    }

    fun isRequested(taskId: Long): Boolean = requested.containsKey(taskId)

    fun clear(taskId: Long) {
        requested.remove(taskId)
    }
}
