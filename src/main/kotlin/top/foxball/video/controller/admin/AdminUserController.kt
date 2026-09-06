package top.foxball.video.controller.admin

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.entity.jdbc.User
import top.foxball.video.service.AccessControlService
import top.foxball.video.service.UserService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@RestController
@Validated
@RequestMapping("/api/admin/users")
class AdminUserController(
    private val users: UserService,
    private val accessControl: AccessControlService,
    private val passwordEncoder: PasswordEncoder,
    private val builder: ResponseBuilder,
) {
    @GetMapping
    @PreAuthorize("hasAuthority('user:write')")
    fun list(
        @RequestParam("keyword", required = false) @Size(max = 100) keyword: String?,
        @RequestParam("enabled", required = false) enabled: Boolean?,
        @RequestParam("role_code", required = false) @Size(max = 50) roleCode: String?,
        @RequestParam("page", defaultValue = "1") @Min(1) page: Int,
        @RequestParam("size", defaultValue = "20") @Min(1) @Max(100) size: Int,
    ): ResponseEntity<Response> {
        data class UserData(
            @param:JsonProperty("user_id") val userId: Long,
            val username: String,
            val email: String,
            val enabled: Boolean,
            val roles: List<String>,
        )
        data class Response(
            val items: List<UserData>,
            val total: Int,
            val page: Int,
            val size: Int,
            @param:JsonProperty("total_pages") val totalPages: Int,
        )

        val normalizedKeyword = keyword?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedRole = roleCode?.trim()?.takeIf { it.isNotEmpty() }
        val filtered = users.findAll().filter { user ->
            (normalizedKeyword == null || user.username.contains(normalizedKeyword, ignoreCase = true) || user.email.contains(normalizedKeyword, ignoreCase = true)) &&
                (enabled == null || user.enabled == enabled) &&
                (normalizedRole == null || user.roles.any { it.code == normalizedRole })
        }
        val totalPages = if (filtered.isEmpty()) 0 else (filtered.size + size - 1) / size
        val offset = (page - 1) * size
        val items = if (offset >= filtered.size) emptyList() else filtered.drop(offset).take(size).map { user ->
            UserData(requireNotNull(user.id), user.username, user.email, user.enabled, user.roles.map { it.code }.sorted())
        }
        val rs = Response(items, filtered.size, page, size, totalPages)
        return builder.ok().data(rs).build()
    }

    @GetMapping("/{user_id}")
    @PreAuthorize("hasAuthority('user:write')")
    fun get(@PathVariable("user_id") userId: Long): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("user_id") val userId: Long,
            val username: String,
            val email: String,
            val enabled: Boolean,
            val roles: List<String>,
        )

        val user = users.findById(userId) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(user.id), user.username, user.email, user.enabled, user.roles.map { it.code }.sorted())
        return builder.ok().data(rs).build()
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:write')")
    fun create(
        @RequestParam("username") @NotBlank @Size(max = 50) username: String,
        @RequestParam("email") @NotBlank @Email @Size(max = 255) email: String,
        @RequestParam("password") @NotBlank @Size(min = 6, max = 100) password: String,
        @RequestParam("role_codes", required = false) roleCodes: Set<String>?,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("user_id") val userId: Long)

        if (users.existsByUsername(username.trim()) || users.existsByEmail(email.trim())) {
            return builder.badRequest().message("用户名或邮箱已存在").build()
        }
        val user = User(
            username = username.trim(),
            email = email.trim(),
            passwordHash = passwordEncoder.encode(password) ?: "",
            enabled = enabled,
        )
        val normalizedRoles = roleCodes?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()?.takeIf { it.isNotEmpty() }
            ?: setOf("USER")
        accessControl.assignRoles(user, normalizedRoles)
        users.save(user)
        val rs = Response(requireNotNull(user.id))
        return builder.created().data(rs).build()
    }

    @PutMapping("/{user_id}")
    @PreAuthorize("hasAuthority('user:write')")
    fun update(
        @PathVariable("user_id") userId: Long,
        @RequestParam("username") @NotBlank @Size(max = 50) username: String,
        @RequestParam("email") @NotBlank @Email @Size(max = 255) email: String,
        @RequestParam("password", required = false) @Size(min = 6, max = 100) password: String?,
        @RequestParam("role_codes", required = false) roleCodes: Set<String>?,
        @RequestParam("enabled", defaultValue = "true") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("user_id") val userId: Long)

        val user = users.findById(userId) ?: return builder.notFound().build()
        val cleanUsername = username.trim()
        val cleanEmail = email.trim()
        if (users.findByUsername(cleanUsername)?.id?.let { it != userId } == true ||
            users.existsByEmail(cleanEmail) && user.email != cleanEmail) {
            return builder.badRequest().message("用户名或邮箱已存在").build()
        }
        user.username = cleanUsername
        user.email = cleanEmail
        user.enabled = enabled
        if (!password.isNullOrBlank()) user.passwordHash = passwordEncoder.encode(password) ?: user.passwordHash
        roleCodes?.map(String::trim)?.filter(String::isNotEmpty)?.toSet()?.takeIf { it.isNotEmpty() }?.let {
            accessControl.assignRoles(user, it)
        }
        users.save(user)
        val rs = Response(requireNotNull(user.id))
        return builder.ok().data(rs).build()
    }

    @PatchMapping("/{user_id}/status")
    @PreAuthorize("hasAuthority('user:write')")
    fun updateStatus(
        @PathVariable("user_id") userId: Long,
        @RequestParam("enabled") enabled: Boolean,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("user_id") val userId: Long, val enabled: Boolean)

        val user = users.findById(userId) ?: return builder.notFound().build()
        user.enabled = enabled
        users.save(user)
        val rs = Response(userId, enabled)
        return builder.ok().data(rs).build()
    }

    @DeleteMapping("/{user_id}")
    @PreAuthorize("hasAuthority('user:write')")
    fun disable(@PathVariable("user_id") userId: Long): ResponseEntity<Response> {
        val user = users.findById(userId) ?: return builder.notFound().build()
        user.enabled = false
        users.save(user)
        return builder.ok().message("用户已停用").build()
    }
}
