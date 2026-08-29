package top.foxball.video.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** 启用文件模块的配置属性绑定。签名器由组件扫描自动装配。 */
@Configuration
@EnableConfigurationProperties(FileProperties::class)
class FileConfig
