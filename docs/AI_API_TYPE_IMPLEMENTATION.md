# AI API 三类型实现说明

## 1. 已实现范围

API 配置现在支持三种互斥的能力类型：

| `api_type` | 用途 | 调用协议 |
| --- | --- | --- |
| `CHAT` | 剧本解析、结构化提取、纯文字对话 | OpenAI `POST /v1/responses`（`openai`）或兼容服务 `POST /v1/chat/completions` |
| `IMAGE` | 角色/场景参考图、文生图、图生图 | `POST /v1/images/generations`、`POST /v1/images/edits` |
| `VIDEO` | 根据文字或图片生成视频片段 | `POST /v1/videos`、`GET /v1/videos/{video_id}`、`GET /v1/videos/{video_id}/content` |

> 兼容性提示：OpenAI 官方视频文档当前标注 Sora 2 Videos API 将于 2026-09-24 下线（当前日期为 2026-09-03）。视频能力因此保留为供应商适配层，配置中不要把 `sora-2` 作为不可替换的业务常量；下线前应接入替代视频提供商或新的官方接口。

类型保存在现有 `ai_model_provider_configs.model` JSON 中：

```json
{"id":"gpt-5.6","api_type":"CHAT"}
```

旧配置没有 `api_type` 时按 `CHAT` 兼容。提供商不再要求全局唯一，因此同一提供商可分别保存三条不同类型配置。

## 2. API 管理

管理接口沿用 `/api/ai/model-providers` CRUD，在新增、编辑和模型探测请求中增加 `api_type`。列表和详情返回 `api_type`；API Key 与代理密码仍不会返回。

前端 `/api` 页面增加类型选择和列表展示：对话、图片、视频。编辑已有配置时类型保持不变，避免误把一个连接切换成不兼容协议。

## 3. 服务端调用

`AiInvocationService` 根据配置类型校验调用：

- 对话调用只能使用 `CHAT` 配置。
- 图片生成和编辑只能使用 `IMAGE` 配置。
- 视频创建、状态查询和内容下载只能使用 `VIDEO` 配置。

校验失败在发起网络请求前抛出配置异常。所有请求继续使用现有代理、DNS、超时、并发限制和 URI 安全策略。

### 3.1 对话

`POST /api/ai/chat/completions` 接收 `provider_config_id`、`model` 和 `prompt`，返回文本、结束原因和 token 用量。剧本解析服务继续使用同一个 `AiInvocationService`，因此业务调用不需要接触 API Key 或上游 HTTP DTO。

### 3.2 图片

`POST /api/ai/images/generations` 接收 `provider_config_id`、`model`、`prompt`、`count`、`size` 和 `quality`。服务端优先处理 base64 响应；如果上游返回临时 URL，则通过 `AiResultDownloader` 校验主机、协议和大小后下载。图片通过 `FileService.storeGenerated` 保存为当前用户文件，响应返回短期签名下载地址。

`POST /api/ai/images/edits` 使用 multipart 的 `images` 和可选 `mask`，用于图生图和局部重绘，结果按与文生图相同的规则保存。

资产页面使用数据库驱动的媒体入口，不把编辑中的提示词或参考图从浏览器传给模型：

- `POST /api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/images/generations`
- `POST /api/projects/{project_id}/episodes/{episode_id}/assets/{asset_id}/images/edits`

这两个入口只接收 `provider_config_id`、`model`、`target`、`count` 和可选 `size`；服务端在调用时读取角色、场景或道具档案，图生图的参考图片从已保存文件读取。资产入口默认使用 `2K`，生成结果必须先写入服务端文件服务，前端只接收签名下载地址。调用前会检查项目编辑权限。

### 3.3 视频

`POST /api/ai/videos` 使用 multipart 请求，可选 `input_reference` 图片，返回上游 `video_id` 和排队状态。视频生成是异步的，客户端通过 `GET /api/ai/videos/{video_id}?provider_config_id=...` 查询状态；完成后调用 `POST /api/ai/videos/{video_id}/content` 将内容下载到服务端并保存为用户文件。

支持 `video`、`thumbnail`、`spritesheet` 三种上游内容变体（当前业务下载默认视频本体）。视频 ID、变体和尺寸均经过格式校验，原始上游错误体不会返回。

## 4. 文件保存

生成内容不直接保存上游临时 URL。图片和视频均写入现有 `file_metadata`/本地文件服务，计算 SHA-256，并通过现有签名下载机制返回。数据库迁移不在本次实现范围内；文件输出暂通过 `StoredFile` 统一管理。

## 5. 验证

- `./gradlew test --tests top.foxball.video.ai.OpenAiCompatibleClientTest --tests top.foxball.video.ai.OpenAiVideoClientTest --no-daemon`
- `pnpm run build`（`frontend`）

新增视频客户端测试覆盖创建、状态轮询和内容下载；既有对话/图片兼容客户端测试继续通过。

## 6. 后续接入点

具体剧本解析、角色/场景参考图和分镜视频业务应创建各自的 `ProductionTask`，将 `provider_config_id`、`model_name`、请求摘要和上游视频 ID 写入任务输入；网络调用完成后再持久化输出文件并更新任务状态。当前通用 HTTP 接口已提供所需的三类调用能力。

## 7. 官方参考

- [OpenAI Text generation](https://developers.openai.com/api/docs/guides/text)：Responses API 文本请求与输出。
- [OpenAI Image generation](https://developers.openai.com/api/docs/guides/images)：Images API 文生图、图像编辑和图像输入。
- [OpenAI Video generation with Sora](https://developers.openai.com/api/docs/guides/video-generation)：Videos API 创建、状态轮询、图片参考和内容下载。
