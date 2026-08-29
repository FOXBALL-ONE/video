package top.foxball.video.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import top.foxball.video.service.AccessControlService
import top.foxball.video.service.UserService
import top.foxball.video.config.TestAdminProperties

data class AuthenticatedUser(
    val id: Long,
    val username: String,
    val email: String,
    val roles: Set<String>,
    val permissions: Set<String>,
)

@Component
class JwtAuthenticationFilter(
    private val tokens: JwtTokenService,
    private val users: UserService,
    private val accessControl: AccessControlService,
    private val testAdmin: TestAdminProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (testAdmin.enabled && header?.startsWith("Bearer ") == true &&
            java.security.MessageDigest.isEqual(
                header.removePrefix("Bearer ").toByteArray(),
                testAdmin.token.toByteArray(),
            )
        ) {
            val testUser = users.findByUsername(testAdmin.username)
            if (testUser != null && testUser.enabled) {
                val testUserId = requireNotNull(testUser.id)
                tokens.activateTestAdmin(testUserId)
                val current = accessControl.authoritiesOf(testUser)
                val authorities = current.roles.map { SimpleGrantedAuthority("ROLE_$it") } + current.permissions.map(::SimpleGrantedAuthority)
                val auth = UsernamePasswordAuthenticationToken(testUserId, null, authorities)
                auth.details = AuthenticatedUser(testUserId, testUser.username, testUser.email, current.roles, current.permissions)
                SecurityContextHolder.getContext().authentication = auth
            }
            chain.doFilter(request, response)
            return
        }
        if (header?.startsWith("Bearer ") == true) {
            tokens.parse(header.removePrefix("Bearer "))?.let { session ->
                val user = users.findById(session.userId)
                if (user == null || !user.enabled) return@let
                val current = accessControl.authoritiesOf(user)
                val authorities = current.roles.map { SimpleGrantedAuthority("ROLE_$it") } +
                    current.permissions.map(::SimpleGrantedAuthority)
                val auth = UsernamePasswordAuthenticationToken(session.userId, null, authorities)
                auth.details = AuthenticatedUser(
                    id = session.userId,
                    username = user.username,
                    email = user.email,
                    roles = current.roles,
                    permissions = current.permissions,
                )
                SecurityContextHolder.getContext().authentication = auth
            }
        }
        chain.doFilter(request, response)
    }

}
