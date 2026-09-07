package top.foxball.video.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.slf4j.LoggerFactory
import jakarta.servlet.DispatcherType
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
    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    /**
     * 图片和视频接口以 Mono/DeferredResult 异步完成，Spring MVC 完成异步处理后会再次分派请求。
     * 重新分派时必须再次解析 Authorization，否则受保护 API 会因认证上下文为空返回 401。
     */
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        val isApiRequest = request.requestURI.startsWith("/api/")
        val isAsyncDispatch = request.dispatcherType != DispatcherType.REQUEST
        if (isApiRequest && isAsyncDispatch) {
            log.info(
                "异步请求重新分派进入JWT过滤器：method={} uri={} dispatcher={} authorizationHeader={}",
                request.method,
                request.requestURI,
                request.dispatcherType,
                if (header.isNullOrBlank()) "未提供" else "已提供",
            )
        }
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
                if (isAsyncDispatch || request.requestURI.contains("/images/")) {
                    log.info("测试管理员JWT认证成功：method={} uri={} dispatcher={} userId={}", request.method, request.requestURI, request.dispatcherType, testUserId)
                }
            }
            chain.doFilter(request, response)
            return
        }
        if (header?.startsWith("Bearer ") == true) {
            val token = header.removePrefix("Bearer ")
            val session = tokens.parse(token)
            if (session == null) {
                log.warn("JWT令牌解析失败：method={} uri={} dispatcher={}", request.method, request.requestURI, request.dispatcherType)
            }
            session?.let { sessionData ->
                val user = users.findById(sessionData.userId)
                if (user == null || !user.enabled) {
                    log.warn("JWT用户不存在或已停用：method={} uri={} dispatcher={} userId={}", request.method, request.requestURI, request.dispatcherType, sessionData.userId)
                    return@let
                }
                val current = accessControl.authoritiesOf(user)
                val authorities = current.roles.map { SimpleGrantedAuthority("ROLE_$it") } +
                    current.permissions.map(::SimpleGrantedAuthority)
                val auth = UsernamePasswordAuthenticationToken(sessionData.userId, null, authorities)
                auth.details = AuthenticatedUser(
                    id = sessionData.userId,
                    username = user.username,
                    email = user.email,
                    roles = current.roles,
                    permissions = current.permissions,
                )
                SecurityContextHolder.getContext().authentication = auth
                if (isAsyncDispatch || request.requestURI.contains("/images/")) {
                    log.info("JWT认证成功：method={} uri={} dispatcher={} userId={}", request.method, request.requestURI, request.dispatcherType, sessionData.userId)
                }
            }
        } else if (isApiRequest && isAsyncDispatch) {
            log.warn("异步API请求未携带JWT：method={} uri={} dispatcher={}", request.method, request.requestURI, request.dispatcherType)
        }
        chain.doFilter(request, response)
    }

}
