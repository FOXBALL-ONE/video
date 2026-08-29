package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.User

/** 用户实体的 JPA 数据访问接口。 */
interface UserRepository : JpaRepository<User, Long> {
    /** 通过主键查询用户，兼容 Service 使用的可空返回值。 */
    fun findUserById(id: Long): User? = findById(id).orElse(null)
    fun findByUsername(username: String): User?
    fun existsByUsername(username: String): Boolean
    fun existsByEmail(email: String): Boolean
}
