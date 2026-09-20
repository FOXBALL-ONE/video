package top.foxball.video.service

import jakarta.transaction.Transactional
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.data.redis.core.StringRedisTemplate
import top.foxball.video.config.TestAdminProperties
import top.foxball.video.entity.jdbc.Permission
import top.foxball.video.entity.jdbc.Role
import top.foxball.video.entity.jdbc.User
import top.foxball.video.repository.PermissionRepository
import top.foxball.video.repository.RoleRepository
import top.foxball.video.repository.UserRepository

data class UserAuthorities(val roles: Set<String>, val permissions: Set<String>)

@Service
class AccessControlService(
    private val roles: RoleRepository,
    private val permissions: PermissionRepository,
    private val users: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val redis: StringRedisTemplate,
    private val testAdmin: TestAdminProperties,
) {
    companion object {
        const val TEST_ADMIN_STATE_KEY = "auth:test-admin:enabled"
    }
    @Transactional
    fun authoritiesOf(user: User): UserAuthorities = UserAuthorities(
        roles = user.roles.map { it.code }.toSet(),
        permissions = user.roles.flatMap { role -> role.permissions.map(Permission::code) }.toSet(),
    )

    @Transactional
    fun defaultRole(): Role = roles.findByCode("USER") ?: error("USER 角色未初始化")

    @Transactional
    fun assignRoles(user: User, roleCodes: Set<String>) {
        val assigned = roles.findAllByCodeIn(roleCodes).toMutableSet()
        require(assigned.size == roleCodes.size) { "包含不存在的角色" }
        user.roles = assigned
    }

    @Transactional
    fun ensureDefaults() {
        val userRead = ensurePermission("user:read", "查看用户")
        val fileWrite = ensurePermission("file:write", "上传和管理文件")
        val userWrite = ensurePermission("user:write", "管理用户")
        val systemAdmin = ensurePermission("system:admin", "系统管理")
        ensureRole("USER", "普通用户", setOf(userRead, fileWrite))
        val admin = ensureRole("ADMIN", "管理员", setOf(userRead, fileWrite, userWrite, systemAdmin))
        if (testAdmin.enabled) {
            val user = users.findByUsername(testAdmin.username)
                ?: users.save(User(username = testAdmin.username, email = testAdmin.email, passwordHash = passwordEncoder.encode("disabled-login") ?: ""))
            user.enabled = true
            user.roles = mutableSetOf(admin)
            users.save(user)
            redis.opsForValue().set(TEST_ADMIN_STATE_KEY, requireNotNull(user.id).toString())
        } else {
            users.findByUsername(testAdmin.username)?.let { user ->
                user.enabled = false
                users.save(user)
            }
            redis.delete(TEST_ADMIN_STATE_KEY)
        }
    }

    private fun ensurePermission(code: String, name: String): Permission =
        permissions.findByCode(code) ?: permissions.save(Permission(code = code, name = name))

    private fun ensureRole(code: String, name: String, granted: Set<Permission>): Role {
        val role = roles.findByCode(code)
        return role ?: roles.save(Role(code = code, name = name, permissions = granted.toMutableSet()))
    }
}

@Configuration
@ConditionalOnProperty(prefix = "auth", name = ["access-control-initializer-enabled"], havingValue = "true", matchIfMissing = true)
class AccessControlInitializer {
    @Bean
    fun initializeAccessControl(service: AccessControlService) = ApplicationRunner { service.ensureDefaults() }
}
