package top.foxball.video.controller

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.validation.annotation.Validated
import top.foxball.video.entity.jdbc.User
import top.foxball.video.config.TestAdminProperties
import top.foxball.video.security.JwtTokenService
import top.foxball.video.service.UserService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@RestController
@Validated
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserService,
    private val encoder: PasswordEncoder,
    private val tokens: JwtTokenService,
    private val accessControl: top.foxball.video.service.AccessControlService,
    private val testAdmin: TestAdminProperties,
    private val builder: ResponseBuilder,
) {
    @PostMapping("/register")
    fun register(
        @RequestParam("username") @NotBlank @Size(max = 50) username: String,
        @RequestParam("email") @NotBlank @Email @Size(max = 255) email: String,
        @RequestParam("password") @NotBlank @Size(max = 100) password: String,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("user_id") val userId: Long)

        if (users.existsByUsername(username) || users.existsByEmail(email)) {
            return builder.badRequest().message("用户名或邮箱已存在").build()
        }
        val user = User(username = username, email = email, passwordHash = encoder.encode(password) ?: "")
        user.roles.add(accessControl.defaultRole())
        users.save(user)
        val rs = Response(requireNotNull(user.id))
        return builder.created().data(rs).build()
    }

    @PostMapping("/login")
    fun login(
        @RequestParam("username") @NotBlank @Size(max = 50) username: String,
        @RequestParam("password") @NotBlank @Size(max = 100) password: String,
    ): ResponseEntity<Response> {
        data class Response(
            @param:JsonProperty("access_token") val accessToken: String,
            @param:JsonProperty("token_type") val tokenType: String,
        )

        val user = users.findByUsername(username)
            ?: return builder.unauthorized().message("用户名或密码错误").build()
        val fixedTestToken = testAdmin.enabled && username == testAdmin.username &&
            java.security.MessageDigest.isEqual(password.toByteArray(), testAdmin.password.toByteArray())
        if (!user.enabled || (!fixedTestToken && !encoder.matches(password, user.passwordHash))) {
            return builder.unauthorized().message("用户名或密码错误").build()
        }
        if (user.roles.isEmpty()) {
            user.roles.add(accessControl.defaultRole())
            users.save(user)
        }
        if (fixedTestToken) {
            tokens.activateTestAdmin(requireNotNull(user.id))
        }
        val rs = Response(if (fixedTestToken) testAdmin.token else tokens.issue(user), "Bearer")
        return builder.ok().data(rs).build()
    }

    @PostMapping("/logout")
    fun logout(@RequestHeader("Authorization") authorization: String): ResponseEntity<Response> {
        if (authorization.startsWith("Bearer ")) {
            val token = authorization.removePrefix("Bearer ")
            if (testAdmin.enabled && token == testAdmin.token) {
                tokens.deactivateTestAdmin()
            } else {
                tokens.revoke(token)
            }
        }
        return builder.ok().build()
    }

    @GetMapping("/me")
    fun me(@org.springframework.security.core.annotation.AuthenticationPrincipal userId: Long): ResponseEntity<Response> {
        data class Response(val id: Long, val username: String, val email: String, val roles: List<String>)

        val user = users.findById(userId) ?: return builder.notFound().build()
        val rs = Response(requireNotNull(user.id), user.username, user.email, user.roles.map { it.code })
        return builder.ok().data(rs).build()
    }

    @GetMapping("/admin-check")
    @PreAuthorize("hasRole('ADMIN')")
    fun adminCheck(): ResponseEntity<Response> = builder.ok().message("管理员权限验证通过").build()

    @PostMapping("/users/{user_id}/roles")
    @PreAuthorize("hasAuthority('user:write')")
    fun assignRoles(
        @PathVariable("user_id") userId: Long,
        @RequestParam("role_codes") roleCodes: Set<String>,
    ): ResponseEntity<Response> {
        data class Response(@param:JsonProperty("user_id") val userId: Long, val roles: Set<String>)

        val user = users.findById(userId) ?: return builder.notFound().build()
        accessControl.assignRoles(user, roleCodes)
        users.save(user)
        val rs = Response(userId, roleCodes)
        return builder.ok().data(rs).build()
    }
}
