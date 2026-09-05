package top.foxball.video.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** RabbitMQ 通用客户端的运行参数，对应 `mq.*`；拓扑名称由代码统一定义。 */
@ConfigurationProperties(prefix = "mq")
data class MqProperties(
    var publishConfirmTimeout: Duration = Duration.ofSeconds(5),
)
