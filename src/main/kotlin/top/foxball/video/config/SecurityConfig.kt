package top.foxball.video.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.core.context.SecurityContextHolder
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.DispatcherType
import org.springframework.http.HttpMethod
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.slf4j.LoggerFactory
import top.foxball.video.security.JwtAuthenticationFilter

@Configuration
class PasswordEncoderConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}

@Configuration
@EnableMethodSecurity
class SecurityConfig(private val jwtAuthenticationFilter: JwtAuthenticationFilter) {
    private val log = LoggerFactory.getLogger(SecurityConfig::class.java)

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration().apply {
            // Keep credentialed CORS restricted to local development hosts. The Nuxt dev
            // server is commonly opened through the machine's LAN address.
            allowedOriginPatterns = listOf(
                "http://localhost:*",
                "http://127.0.0.1:*",
                "http://192.168.1.35:*",
            )
            allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
            allowCredentials = true
        }
        return UrlBasedCorsConfigurationSource().also { it.registerCorsConfiguration("/**", configuration) }
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .csrf { it.disable() }
        .cors { }
        .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        .authorizeHttpRequests {
            it.requestMatchers("/api/auth/**", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/files/*/download").permitAll()
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
        }
        .exceptionHandling {
            it.authenticationEntryPoint { request, response, exception ->
                log.warn(
                    "认证入口返回401：method={} uri={} dispatcher={} query={} principal={} exception={}",
                    request.method,
                    request.requestURI,
                    request.dispatcherType,
                    request.queryString ?: "",
                    SecurityContextHolder.getContext().authentication?.name ?: "无认证主体",
                    exception.message ?: exception.javaClass.simpleName,
                )
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.contentType = "application/json;charset=UTF-8"
                response.writer.write("{\"status\":401,\"message\":\"未授权\",\"data\":{}}")
            }
            it.accessDeniedHandler { request, response, exception ->
                log.warn(
                    "访问被拒绝：method={} uri={} dispatcher={} query={} exception={}",
                    request.method,
                    request.requestURI,
                    request.dispatcherType,
                    request.queryString ?: "",
                    exception.message ?: exception.javaClass.simpleName,
                )
                response.status = HttpServletResponse.SC_FORBIDDEN
                response.contentType = "application/json;charset=UTF-8"
                response.writer.write("{\"status\":403,\"message\":\"禁止访问\",\"data\":{}}")
            }
        }
        .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        .build()
}
