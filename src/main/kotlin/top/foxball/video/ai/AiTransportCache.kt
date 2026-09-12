package top.foxball.video.ai

/**
 * 按连接档案缓存底层传输资源的组件。提供商连接配置变化时由 [AiProviderResolver] 统一失效缓存，
 * 避免旧的代理、密钥或超时设置继续生效。
 */
interface AiTransportCache {
    /** 丢弃指定连接档案键下的全部缓存资源；键不存在时不做任何事。 */
    fun invalidate(cacheKey: String)
}
