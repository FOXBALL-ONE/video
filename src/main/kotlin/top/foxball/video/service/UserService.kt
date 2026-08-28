package top.foxball.video.service

import org.springframework.stereotype.Service
import top.foxball.video.entity.jdbc.User
import top.foxball.video.repository.UserRepository

@Service
class UserService(
    private  var userRepository: UserRepository
) {
    
    fun findById(id: Long): User?{
        return userRepository.findUserById(id)
    }
    
    fun save(user: User): User {
        return userRepository.save(user)
    }
}
