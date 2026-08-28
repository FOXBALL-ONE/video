package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.User

interface UserRepository : JpaRepository<User, Long> {
    fun findUserById(id: Long): User? = findById(id).orElse(null)
}
