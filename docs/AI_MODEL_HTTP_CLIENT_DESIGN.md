# AI 大模型 HTTP 客户端设计

## 1. 目标

本文定义一个运行在服务端、面向 OpenAI 兼容协议的 AI 大模型 HTTP 客户端。本文仅描述设计，不新增依赖、不修改数据库结构、不改变现有接口行为。

该客户端将成为应用调用上游模型服务的唯一入口。业务服务不得自行创建 `RestClient`、`WebClient` 或原始 HTTP 请求。

## 2. 范围

首期客户端覆盖以下 OpenAI 兼容能力：

| 能力 | 上游接口 | 请求格式 | 主要场景 |
| --- | --- | --- | --- |
| 获取模型列表 | `GET /v1/models` | JSON 响应 | API 管理、模型选择 |
| 对话补全 | `POST /v1/chat/completions` | JSON | 剧本分析、创作助手、结构化提取 |
| 流式对话补全 | `POST /v1/responses`，`stream=true` | Server-Sent Events | 交互式助手输出 |
| 文生图 | `POST /v1/images/generations` | JSON | 概念图、资产图、分镜图 |
| 图像编辑或局部重绘 | `POST /v1/images/edits` | `multipart/form-data` | 资产修订、蒙版编辑 |
| 视频生成 | `POST /v1/videos`、`GET /v1/videos/{id}`、`GET /v1/videos/{id}/content` | JSON 或 multipart | 文字/图片生成视频片段 |

三类 API 配置、视频异步状态及生成结果保存的落地说明见 [`AI_API_TYPE_IMPLEMENTATION.md`](AI_API_TYPE_IMPLEMENTATION.md)。

`/v1/images/generations` 与 `/v1/images/edits` 是两个明确分离的操作。业务调用方必须显式选择其一，不能依赖语义模糊的通用图像接口。

首期不包含音频、向量、批量任务、微调、供应商私有参数、浏览器直连模型服务，以及供应商自动降级切换。

## 3. 设计约束与决策

1. 所有模型请求均由后端发起。API Key、代理凭据、敏感提示词和原始上游响应不得在前端代码中出现，除非业务接口明确返回脱敏后的结果。
2. 协议以 OpenAI 兼容 HTTP 规范为基准，不依赖某个特定 SDK。客户端负责 URL 构造、JSON 或 multipart 序列化；业务层只依赖项目领域对象。
3. 提供商地址按服务根地址配置。客户端必须将其解析为 URI，拒绝 user-info、query 与 fragment；仅允许受控的路径前缀。客户端去除末尾斜杠与路径末尾的 `/v1` 后，再以 URI 路径拼接固定协议接口，不能用未经校验的字符串连接。例如 `https://api.example.com` 和 `https://api.example.com/v1` 都会请求 `https://api.example.com/v1/models`。
4. HTTP 代理与 SOCKS5 代理是连接配置的一部分，不能使用 JVM 全局代理属性。一个进程可能需要通过不同代理访问不同模型服务。
5. 首选传输层为 Reactor Netty 加 Spring `WebClient`。它可以按客户端配置 HTTP/SOCKS5 代理并支持流式响应。现有模型列表查询中的单独 `RestClient` 调用将在客户端落地后迁移，不能长期保留两套上游传输实现。
6. 当前项目使用 Spring MVC。客户端返回 Reactor `Mono` 和 `Flux`；控制器中禁止调用 `.block()`。耗时的图像生成和非流式生成应由业务任务调度，流式对话通过专用 SSE 接口适配，前端可显式传入 `stream=false` 回退为完整 JSON。
7. 当前 `AiModelProviderConfig.provider` 具有唯一约束，无法承载同一供应商的多个连接、代理或环境档案。后续实现以“连接档案”而非提供商名称作为客户端选择单位；本轮只定义模型，不修改表结构。
8. 默认模型仅用于初始化界面和任务建议。每次调用都必须携带明确模型 ID；模型目录不能写回或覆盖默认模型。

## 4. 分层架构

```text
剧本 / 资产 / 制作业务服务
              |
              v
       AiInvocationService
              |
              v
         AiModelClient 门面
        /          |           \
       v           v            v
  ChatClient   ImageClient   ModelCatalogClient
        \          |           /
         v         v          v
        OpenAiProtocolMapper
              |
              v
   ProxyAwareHttpTransportFactory
              |
              v
   OpenAI 兼容的大模型服务
```

### 4.1 模块职责

| 组件 | 职责 |
| --- | --- |
| `AiInvocationService` | 解析提供商配置，校验能力与模型，分配关联 ID，并负责与业务任务生命周期集成。 |
| `AiModelClient` | 面向应用的稳定接口，提供模型列表、对话、流式对话、图像生成与图像编辑。 |
| `OpenAiCompatibleClient` | `AiModelClient` 的实现，将领域命令映射为 OpenAI 兼容 HTTP 请求，并将上游响应映射为领域结果。 |
| `AiProviderResolver` | 加载已启用的 `AiModelProviderConfig`，解析密钥、协议版本和连接配置。 |
| `ProxyAwareHttpTransportFactory` | 按不可变连接配置创建或复用 `WebClient`，包括 TLS、代理、超时和 DNS 策略。 |
| `OpenAiProtocolMapper` | 序列化 JSON 与 multipart 请求，解析普通 JSON 和 SSE 数据；上游 DTO 不得泄漏到客户端边界之外。 |
| `AiErrorMapper` | 将上游与传输异常转换为强类型领域异常。 |
| `AiInvocationAuditService` | 写入已脱敏的审计和指标事件。 |

建议包路径为 `top.foxball.video.ai`。该代码不能放进控制器包，也不能混入仅负责配置 CRUD 的 `AiModelProviderConfigService`。

### 4.2 配置与模型目录

后续实现引入以下逻辑模型。它们可先由现有配置与应用配置组合提供，持久化设计另行评审：

| 模型 | 关键字段 | 用途 |
| --- | --- | --- |
| `AiProviderConnectionProfile` | `id`、协议、根 URI、API Key 密钥引用、能力集、默认模型、连接档案引用、配置版本 | 一次上游调用的完整连接身份；调用方只传 `id`。 |
| `AiConnectionProfile` | 代理模式、主机、端口、代理密钥引用、DNS 模式、超时、非代理主机、私网开关、版本 | 与模型协议无关的网络传输配置。 |
| `ProviderModelCatalog` | 连接档案 ID、配置版本、模型 ID 列表、获取时间、过期时间、失败摘要 | 缓存 `/v1/models` 结果，减少请求和管理页等待。 |
| `AiSecretResolver` | 密钥引用、轮换版本、读取权限 | 从专用密钥存储或加密字段获取提供商与代理密钥，调用链中只短暂保留明文。 |

模型目录以连接档案 ID 和配置版本作为缓存键，默认有效期为 10 分钟。成功的模型查询可刷新缓存；失败不能清空尚未过期的最近成功结果。密钥、代理密码和完整上游错误体绝不进入缓存。

现有 `api_key` 字段在实现前需要完成密钥存储评审。至少应采用应用级信封加密或外部密钥存储，并支持密钥轮换；不能把该字段的当前存储形式扩散到新连接或代理配置。

### 4.3 调用生命周期

一次业务调用必须有唯一 `invocationId`，其状态机如下：

```text
QUEUED -> VALIDATING -> RUNNING -> SUCCEEDED -> RESULT_PERSISTED
                         |
                         v
                       FAILED

QUEUED / VALIDATING / RUNNING -> CANCELLED
```

`VALIDATING` 阶段完成提供商启用状态、调用权限、能力、模型目录、请求大小和网络策略校验。`RUNNING` 之前持久化调用快照：连接档案 ID 与版本、模型、端点类别、超时档案、代理配置版本、业务关联 ID 和关联 ID；不得持久化 API Key、代理密码或完整提示词。

任何 JPA 事务均不得跨越上游网络调用。任务状态先完成一次短事务更新，再调用客户端；取得结果后在新的短事务中持久化结果和最终状态。任务重试必须复用 `invocationId` 并增加 attempt 编号，不能新建没有关联关系的重复调用。

## 5. 公共客户端契约

最终 Kotlin 命名可以调整，但边界应保持如下形式：

```kotlin
interface AiModelClient {
    fun listModels(command: ListModelsCommand): Mono<List<ModelDescriptor>>
    fun createChatCompletion(command: ChatCompletionCommand): Mono<ChatCompletionResult>
    fun streamChatCompletion(command: ChatCompletionCommand): Flux<ChatCompletionDelta>
    fun generateImage(command: ImageGenerationCommand): Mono<ImageGenerationResult>
    fun editImage(command: ImageEditCommand): Mono<ImageGenerationResult>
}
```

每个命令均包含 `providerConfigId`、`model` 和操作所需的专属输入。提供商密钥、服务地址与代理密钥只能在客户端内部解析，调用方不能传入任意请求头、URL 片段或代理设置。

模型必须存在于该连接档案当前或未过期的模型目录中；模型目录不可用时，调用方必须显式刷新并获得结果。仅管理员批准的连接档案可以开启 `allowUnlistedModel`，用于不提供 `/v1/models` 的兼容服务，且每次调用都要产生审计事件。

### 5.1 通用命令字段

| 字段 | 说明 |
| --- | --- |
| `providerConfigId` | 要使用的、已启用的提供商配置。 |
| `model` | 调用方或任务明确选择的模型 ID。 |
| `requestId` | 应用生成的关联 ID；仅在安全且受支持时透传为请求头。 |
| `timeoutProfile` | 按操作类型选择的命名超时配置，调用方不能直接传入毫秒数。 |
| `metadata` | 项目、剧集、任务等已脱敏业务标识，仅用于审计；除非能力声明允许，否则不传给上游。 |

### 5.2 对话补全

`ChatCompletionCommand` 支持 system、user、assistant 和 tool 消息。消息内容可以是文本，或显式支持的图片引用。工具调用必须用强类型请求与结果表示，不能将无边界的供应商 JSON 直接传给业务层。

必须满足：

- 普通模式发送 `stream=false`，返回全部 assistant choices 和上游提供的 usage 数据，并提供只读的 `primaryChoice` 便利访问器。
- 流式模式统一发送到 Responses API 并设置 `stream=true`，增量读取语义化 SSE，忽略心跳和非文本事件，识别 `response.completed`，按顺序发出 `ChatCompletionDelta`。
- 映射器保留上游的结束原因、工具调用与 usage 元数据。
- 流式工具调用只发出不可变增量；拼接参数和执行工具由上层编排服务完成，客户端不得在收到不完整参数时调用工具。
- 对供应商私有参数，必须在校验阶段明确拒绝，不能静默丢弃。

### 5.3 图像生成

`ImageGenerationCommand` 对应 `/v1/images/generations`，输入包括提示词、模型、图片数量、尺寸、响应表示偏好以及受控的质量或风格选项。客户端将结果标准化为下列之一：

- 具有过期信息的上游临时 URL；或
- 已解码的图片字节，随后由调用方通过 `FileService` 持久化为项目文件引用。

上游临时 URL 未复制数据前，不能直接作为项目长期资产保存。下载临时 URL 时使用独立的 `AiResultDownloader`，执行与模型请求相同的协议、重定向、DNS 和大小限制校验；不能让文件服务直接请求任意上游 URL。

### 5.4 图像编辑

`ImageEditCommand` 对应 `/v1/images/edits`，使用 multipart 请求。输入包括原始图片文件引用、可选蒙版文件引用、提示词、模型、数量和尺寸。`FileService` 负责解析文件引用并以流方式写入 multipart。发起上游请求前，客户端必须校验原图与蒙版的媒体类型和大小。

## 6. OpenAI 兼容协议映射

所有出站请求使用：

```text
Authorization: Bearer <provider secret>
Accept: application/json
User-Agent: video-ai-client/<application version>
```

对话与图像生成使用 JSON 请求体，图像编辑使用 multipart。提供商地址在验证后解析成 URI 并结合固定协议路径，不能直接拼接未经约束的字符串。

兼容层至少识别以下标准响应形状：

```json
{ "data": [{ "id": "model-id" }] }
```

```json
{ "choices": [{ "message": { "content": "..." }, "finish_reason": "stop" }] }
```

```json
{ "data": [{ "url": "https://..." }] }
```

也支持携带 base64 数据的图片项。缺失必要字段或响应不兼容时，抛出 `AiProtocolException`；异常中只能记录上游端点与安全摘要，不能记录请求头、API Key、图片字节或完整提示词。

提供商能力必须显式声明：

| 能力 | 首期要求 |
| --- | --- |
| `OPENAI_CHAT_COMPLETIONS` | 必须 |
| `OPENAI_CHAT_STREAMING` | 交互式提供商必须 |
| `OPENAI_IMAGE_GENERATION` | 可选 |
| `OPENAI_IMAGE_EDIT` | 可选 |
| `OPENAI_IMAGE_B64_RESPONSE` | 可选 |

在任何 HTTP 请求前，客户端必须拒绝调用所选提供商未声明的能力。

## 7. 代理设计

### 7.1 连接配置

```text
ProxyMode = DIRECT | HTTP | SOCKS5

AiConnectionProfile
  - proxyMode
  - proxyHost
  - proxyPort
  - proxyUsernameSecretRef（可选）
  - proxyPasswordSecretRef（可选）
  - nonProxyHosts
  - connectTimeout
  - responseHeaderTimeout
  - idleReadTimeout
  - allowPrivateNetwork
```

连接配置在服务端解析，并关联到提供商配置。代理凭据与 API Key 使用相同的密钥保护策略，日志与指标必须脱敏。

`ProxyAwareHttpTransportFactory` 以不包含密钥的连接配置版本哈希缓存不可变 `WebClient`。提供商或连接配置更新时，淘汰相应缓存项。任何代理设置均不得修改 JVM 全局属性。

### 7.2 网络策略

- 默认使用 `DIRECT`。
- `HTTP` 代理通过 CONNECT 访问 HTTPS 模型服务。
- `SOCKS5` 支持可选的用户名和密码认证。
- 代理选择前先匹配 `nonProxyHosts`。
- 仅允许 `http` 与 `https` 提供商地址。
- DNS 模式分为 `LOCAL` 与 `PROXY`。`LOCAL` 为默认值：本地解析后拒绝环回、链路本地、多播和私网目标；仅在管理员明确批准本地模型服务时，`allowPrivateNetwork` 才可开启。`PROXY` 仅允许批准的公开域名，且不能作为绕过本地私网校验的默认路径。
- 代理连接失败时不得自动回退为直连，防止绕过组织网络策略。
- 默认不跟随重定向。需要重定向时，必须逐跳校验目标的协议、主机与网络策略。

## 8. 可靠性、限流与流式响应

| 操作 | 连接超时 | 响应超时 | 自动重试 |
| --- | ---: | ---: | --- |
| 模型列表 | 5 秒 | 15 秒 | 网络错误、429、5xx，最多 2 次 |
| 对话补全 | 10 秒 | 90 秒 | 默认关闭；仅在调用方和提供商能力均保证幂等时开启 |
| 流式对话 | 10 秒 | 首个 SSE 事件前 30 秒，之后为读取空闲超时 | 发出任何增量后不自动重试 |
| 图像生成或编辑 | 10 秒 | 180 秒 | 默认关闭，避免超时后出现重复计费或不确定结果 |

重试采用带抖动的指数退避，并遵守 `Retry-After`。同一业务请求的每次尝试具有独立审计事件，但共用一个应用请求 ID。

SSE 解析器必须处理：事件跨帧拆分、单帧包含多个事件、空心跳、非法 JSON、终止标记与客户端取消。取消时关闭上游连接；只有尚未持久化结果时，相关任务才转换为 `CANCELLED`。

### 8.1 并发控制与断路

每个连接档案维护独立的并发上限、等待队列和断路器，避免一个供应商的限流或代理故障拖垮其他供应商。默认策略如下：

| 控制项 | 默认策略 |
| --- | --- |
| 在途请求数 | 对话 8 个、图像 2 个、模型列表 2 个；后续按连接档案配置。 |
| 等待队列 | 有界队列，满时立即返回可重试的 `AiRateLimitException`，不得无限积压。 |
| 断路器 | 连续 5 个可恢复传输或 5xx 失败后打开 30 秒；401/403、参数错误和协议错误不计入断路。 |
| 半开探测 | 每个连接档案一次仅允许 1 个探测请求，成功后关闭断路器。 |
| 请求体限制 | 文本、图片和蒙版在客户端入口完成大小限制，不能把超大请求排入队列。 |

指标至少包括连接档案维度的请求数、成功率、上游状态码、耗时、队列等待、重试次数、断路器状态、输入输出 token 与图像数量。模型 ID 作为低基数维度，仅允许在受控白名单内上报。

## 9. 错误模型

| 异常 | 常见原因 | 调用方行为 |
| --- | --- | --- |
| `AiConfigurationException` | 提供商不存在或禁用、服务地址无效、能力不支持 | 返回明确的 4xx 业务错误，不请求上游。 |
| `AiProxyException` | 代理不可用、代理认证失败、代理配置无效 | 仅在传输策略允许时将任务标为可重试。 |
| `AiUpstreamAuthenticationException` | 上游 401/403 | 禁止自动重试，提示管理员更新提供商密钥。 |
| `AiRateLimitException` | 上游 429 | 保留重试时间；可重试任务按计划延迟。 |
| `AiUpstreamException` | 其他上游 4xx/5xx | 仅保存安全的状态码或错误码，不暴露原始响应体。 |
| `AiProtocolException` | JSON、SSE 或 multipart 响应不兼容 | 立即失败并记录脱敏的结构摘要。 |
| `AiTransportException` | DNS、TLS、超时、连接重置 | 仅按操作策略重试。 |

控制器继续使用项目的 `ResponseBuilder`：参数或配置错误映射到 `400`，缺失配置映射到 `404`，限流映射到 `429`，可恢复的代理或上游错误映射到 `503`，必要时附带重试提示。

## 10. 安全与审计

1. API Key 与代理凭据均为只写字段，不能从控制器返回，不能出现在异常信息和请求 URL 中。
2. 日志中的 `Authorization`、`Proxy-Authorization`、Cookie 以及配置的其他密钥头必须被脱敏。
3. 提示词与生成文本不能写入应用日志。审计仅保存长度、必要时的关联哈希、模型、提供商配置 ID、端点类别、状态、耗时、重试次数与 token 或图片数量。
4. 图片字节、蒙版和 base64 响应不能写日志；文件必须以项目和用户范围通过现有文件服务保存。
5. 只有管理员可维护提供商与代理配置；调用权限必须与密钥管理权限分离。
6. 每个请求都必须具有贯穿任务、审计和结构化日志的关联 ID。
7. 上游图片 URL、错误消息和响应头均视为不可信输入。它们在被持久化、展示或再次下载前必须经过长度、协议、主机和内容类型校验。
8. 审计记录按业务合规策略设置保留期限。任务删除或项目删除后，审计仅保留不可逆的统计信息，不保留可还原的提示词内容。

## 11. 与现有项目的集成

当前 `AiModelProviderConfig` 已提供提供商标识、服务地址、密钥、默认模型和启用状态，但尚未表达协议版本、能力清单、代理配置或超时配置。这些字段属于后续存储设计，本设计文档不创建或修改任何迁移。

现有 `AiModelProviderConfigService.fetchModelIds` 只覆盖 `/v1/models`。新客户端落地后，应由 `AiModelClient.listModels` 替换，同时保持 `/api/ai/model-providers/models` 面向前端的响应结构不变。

`ProductionTask` 已有 `providerConfigId` 与 `modelName` 字段。图像生成或编辑任务应在调度前写入这些字段。剧本解析从本地规则升级为远程模型调用时，应将用户选择的提供商与模型作为调用元数据记录。

## 12. 交付阶段

1. 建立领域命令和结果、强类型异常、提供商解析器、连接配置模型、代理感知传输工厂；暂不接入业务调用。
2. 实现 `listModels`，迁移现有 API 管理与模型选择接口，且不改变前端响应。
3. 实现非流式对话补全，并添加模拟协议测试。
4. 实现流式对话、SSE 解析测试和受控的流式控制器适配。
5. 实现图像生成，安全持久化返回的 URL 或字节，并接入 `ProductionTask`。
6. 实现图像编辑，补充原图、蒙版和 multipart 测试。
7. 接入任务调度、能力控制、指标、审计和管理端代理配置。

所有阶段均不得允许前端直接调用模型提供商。

当前 API 管理页面仍使用 Spring MVC 的同步模型列表响应作为兼容层。新客户端本身维持 `Mono`/`Flux` 契约；将模型列表控制器改为响应式响应或后台刷新任务属于后续收敛项。

## 13. 验收测试

- 使用模拟 OpenAI 兼容服务验证 `/v1/models`、`/v1/chat/completions`、`/v1/responses`、`/v1/images/generations` 和 `/v1/images/edits` 的路径、Bearer 认证、JSON 与 multipart 边界。
- 对话测试覆盖文本消息、工具调用、非法 choices、带 `Retry-After` 的 429、5xx 和超时。
- SSE 测试覆盖单事件跨帧、单帧多事件、中途取消和非法终止事件。
- 图像生成测试覆盖 URL 与 base64 响应；图像编辑测试覆盖原图、蒙版上传和媒体类型拒绝。
- HTTP 代理与 SOCKS5 测试替身验证请求实际走过指定代理，并验证直连与非代理主机规则。
- 安全测试证明密钥和提示词不会出现在日志、异常、审计事件、控制器响应或 URL 中。
- SSRF 测试拒绝非法协议、未经批准的私网目标和不安全重定向。
- 生命周期测试验证网络调用不处于 JPA 事务内，取消、重试和结果持久化符合 `invocationId` 状态机。
- 容错测试验证有界队列、连接档案级断路器、代理故障隔离，以及 401/403 不触发重试或断路。
- 下载测试验证上游图片 URL 不能绕过 `AiResultDownloader` 的主机、DNS、重定向和文件大小校验。

## 14. 实现前的协议复核

实现前必须根据最新的 OpenAI 官方 Chat Completions 与 Image API 文档，逐字段核对请求和响应的可用性与约束。本设计以端点和客户端边界为基准，不能仅根据提供商名称推断兼容性。

本文编写时曾尝试访问 `platform.openai.com` 与 `developers.openai.com` 的官方文档，但当前环境无法完成 TLS 握手。因此本文不声称已经对实时官方文档完成字段级验证。

## 15. 方案迭代记录

### 第一轮：连接与协议边界

- 将“提供商”与“连接档案”分离，解决同一提供商存在多个服务地址、代理或环境时的唯一约束问题。
- 增加模型目录缓存、配置版本和密钥轮换边界，避免模型列表和密钥生命周期混入业务调用。
- 将 URI 拼接、HTTP/SOCKS5 代理和 DNS 模式纳入安全策略，特别限制 SOCKS5 远端 DNS 成为 SSRF 绕过路径。

### 第二轮：调用生命周期与运行治理

- 引入 `invocationId` 状态机和短事务边界，确保网络调用不占用数据库事务，重试可追踪且不会孤立重复执行。
- 调整对话结果为保留全部 choices；流式工具调用只输出增量，不在客户端内执行工具。
- 增加连接档案级并发上限、有界队列、断路器、指标和安全的上游图片下载路径，降低上游故障、限流与不可信 URL 的影响。
