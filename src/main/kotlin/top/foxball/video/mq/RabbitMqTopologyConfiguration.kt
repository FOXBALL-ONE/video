package top.foxball.video.mq

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import top.foxball.video.config.MqProperties

/** 启用 MQ 运行参数绑定；队列与交换机名称由 [MqTopologyDefinitions] 在代码中维护。 */
@Configuration
@EnableConfigurationProperties(MqProperties::class)
class RabbitMqTopologyConfiguration
