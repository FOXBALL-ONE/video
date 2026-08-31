package top.foxball.video

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import top.foxball.video.config.JwtProperties
import top.foxball.video.config.TestAdminProperties
import top.foxball.video.config.AiClientProperties
import io.github.cdimascio.dotenv.dotenv

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties::class, TestAdminProperties::class, AiClientProperties::class)
class VideoApplication

fun main(args: Array<String>) {
    // Load local .env values as system properties before Spring resolves configuration.
    dotenv {
        ignoreIfMissing = true
    }.entries().forEach { entry ->
        if (System.getProperty(entry.key) == null && System.getenv(entry.key) == null) {
            System.setProperty(entry.key, entry.value)
        }
    }
    runApplication<VideoApplication>(*args)
}
