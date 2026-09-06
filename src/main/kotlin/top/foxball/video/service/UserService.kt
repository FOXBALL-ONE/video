package top.foxball.video.service

import org.springframework.stereotype.Service
import org.springframework.data.domain.Sort
import top.foxball.video.entity.jdbc.User
import top.foxball.video.repository.UserRepository

@Service
/** 用户业务服务，负责委托 JPA 仓储完成用户读写。 */
class UserService(
    private  var userRepository: UserRepository
) {
    
    /** 按主键查询用户，不存在时返回 null。 */
    fun findById(id: Long): User?{
        return userRepository.findUserById(id)
    }

    /** 查询管理端用户列表，按最近创建的用户优先。 */
    fun findAll(): List<User> = userRepository.findAll(Sort.by(Sort.Direction.DESC, "id"))
    
    /** 保存新用户或更新已有用户。 */
    fun save(user: User): User {
        return userRepository.save(user)
    }

    fun findByUsername(username: String): User? = userRepository.findByUsername(username)

    fun existsByUsername(username: String): Boolean = userRepository.existsByUsername(username)

    fun existsByEmail(email: String): Boolean = userRepository.existsByEmail(email)
}
