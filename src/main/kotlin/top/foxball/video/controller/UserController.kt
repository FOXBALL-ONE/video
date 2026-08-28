package top.foxball.video.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import top.foxball.video.service.UserService
import top.foxball.video.shared.Response
import top.foxball.video.shared.ResponseBuilder

@RestController
class UserController(
    private val userService: UserService,
    private val responseBuilder: ResponseBuilder,
) {
    @GetMapping("/api/users/{id}")
    fun getUser(@PathVariable("id") id: Long): ResponseEntity<Response> {
        data class UserData(
            val id: Long,
            val username: String,
            val email: String
        )
        
        val user = userService.findById(id)
            ?: return responseBuilder.notFound().build()
        val rs = UserData(
            requireNotNull(user.id),
            user.username,
            user.email
        )
        return responseBuilder.ok().data(rs).build()
    }
}
