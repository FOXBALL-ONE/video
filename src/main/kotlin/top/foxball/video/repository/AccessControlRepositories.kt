package top.foxball.video.repository

import org.springframework.data.jpa.repository.JpaRepository
import top.foxball.video.entity.jdbc.Permission
import top.foxball.video.entity.jdbc.Role

interface PermissionRepository : JpaRepository<Permission, Long> {
    fun findByCode(code: String): Permission?
    fun findAllByCodeIn(codes: Collection<String>): List<Permission>
}

interface RoleRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?
    fun findAllByCodeIn(codes: Collection<String>): List<Role>
}
