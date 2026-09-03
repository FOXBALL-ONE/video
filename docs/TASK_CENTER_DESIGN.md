# 任务中心设计

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | 设计稿，待评审 |
| 适用版本 | 当前 Video 项目 |
| 负责人 | 待指定 |
| 关联模块 | AI 调用、资产、分镜、视频制作、文件服务、项目权限 |

本文定义一个统一的任务中心，用于查看项目及剧集下的异步任务，并对尚未完成的任务发起取消。任务中心不负责替代具体业务编排器：图像生成、配音、字幕、视频合成等执行器仍由各自领域服务实现，任务中心负责统一生命周期、查询、取消、审计和前端展示。

本文是实现设计，不创建或修改数据库迁移脚本。目标数据结构和索引需要在实现前单独完成数据库评审。

## 2. 背景与现状

当前项目已经有以下相关能力：

- `ProductionTask` 持久化在 `production_tasks`，包含项目、剧集、项目版本、任务类型、状态、进度、AI 提供商配置、模型、输入 JSON、输出文件、错误信息和生命周期时间。
- `ProductionTaskStatus` 当前包含 `PENDING`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`。
- `VideoWorkspaceService.queue` 会为一次制作队列批量创建画面、配音、音乐、字幕和合成任务，但目前只负责入库，没有统一任务查询和真正的调度/执行闭环。
- `EpisodeTaskCard` 是剧集创作约束和版本卡片，不是可执行任务，不能与制作任务混用。
- AI 客户端设计要求每次调用具有唯一关联 ID、短事务状态更新、网络调用与数据库事务隔离，并支持客户端取消上游请求。
- 项目已有 Redis、Spring MVC、WebFlux、WebSocket 和统一 `ResponseBuilder`，前端使用 Nuxt/Vue + TypeScript。

因此，首期应在 `ProductionTask` 之上建立任务中心服务契约，逐步接入真正的执行器；不应让各业务页面分别实现一套任务轮询和取消逻辑。

## 3. 目标与非目标

### 3.1 目标

1. 在一个入口查看项目级和剧集级任务，支持按状态、类型、剧集和时间筛选。
2. 展示任务进度、当前阶段、模型/提供商、创建时间、耗时、输出和安全的失败原因。
3. 对 `PENDING`、`QUEUED`、`RUNNING` 任务提供幂等的取消操作，并正确处理排队取消、执行中取消和取消竞态。
4. 让 AI 生成、素材提取、配音、音乐/音效、字幕、视频合成等任务使用同一状态和审计模型。
5. 在不暴露 API Key、完整提示词、原始上游响应和其他敏感数据的前提下，提供可诊断的错误和审计信息。
6. 支持轮询作为首期通知方案，并为后续 SSE/WebSocket 推送保留事件契约。

### 3.2 非目标

- 本期不设计通用工作流编排器、DAG 编辑器或跨项目批处理界面。
- 本期不允许前端直接调用模型供应商，也不把供应商私有请求体暴露给前端。
- 本期不提供删除任务、修改已提交任务输入或恢复已完成任务；任务记录是审计事实。
- 本期不承诺所有供应商都能中断已发出的网络请求；取消语义必须允许“尽力取消”。
- 本期不新增或修改数据库迁移脚本。

## 4. 核心概念

### 4.1 任务与任务卡

| 概念 | 定义 | 示例 |
| --- | --- | --- |
| 创作任务卡 | 人工维护的剧集创作约束和版本记录 | 主目标、必须包含、禁止改动 |
| 可执行任务 | 由系统排队并交给执行器运行的异步工作单元 | 文生图、配音、字幕、合成 |
| 任务中心 | 可执行任务的统一查询和控制边界 | 列表、详情、取消、事件 |

任务卡可以作为可执行任务的输入来源，但两者的 ID、状态和权限不互相替代。

### 4.2 任务类型

首期任务类型注册表如下。名称是稳定的机器值，展示名称由前端映射，新增类型不应改变已有类型含义。

| `task_type` | 展示名称 | 执行器 | 典型输出 |
| --- | --- | --- | --- |
| `SCRIPT_ANALYSIS` | 剧本解析 | `ScriptAnalysisExecutor` | 结构化剧本数据 |
| `ASSET_EXTRACTION` | 资产提取/生成 | `AssetExecutor` | 资产记录、图片文件 |
| `IMAGE_GENERATION` | 图片生成 | `ImageGenerationExecutor` | 图片文件 |
| `IMAGE_EDIT` | 图片编辑 | `ImageEditExecutor` | 图片文件 |
| `VOICEOVER` | 配音 | `VoiceoverExecutor` | 音频文件 |
| `MUSIC` | 音乐生成 | `MusicExecutor` | 音频文件 |
| `SOUND_EFFECT` | 音效生成 | `SoundEffectExecutor` | 音频文件 |
| `SUBTITLE` | 字幕生成 | `SubtitleExecutor` | 字幕文件或结构化字幕 |
| `COMPOSITION` | 视频合成 | `CompositionExecutor` | 视频文件 |

现有 `ProductionTaskType` 已覆盖大部分制作类型；`SCRIPT_ANALYSIS`、`ASSET_EXTRACTION` 和 `IMAGE_EDIT` 在接入时需要做枚举或适配层评审，不能在控制器中用任意字符串绕过类型校验。

## 5. 生命周期状态机

### 5.1 目标状态

```text
PENDING -> QUEUED -> RUNNING -> SUCCEEDED
    |          |         |
    |          |         +--> CANCEL_REQUESTED -> CANCELLED
    |          +------------> CANCELLED
    +-----------------------> CANCELLED

PENDING / QUEUED / RUNNING -> FAILED
```

状态含义：

| 状态 | 含义 | 是否允许取消 |
| --- | --- | --- |
| `PENDING` | 已创建，正在校验或等待入队 | 是 |
| `QUEUED` | 已进入有界队列，尚未被执行器领取 | 是 |
| `RUNNING` | 执行器已领取并开始工作 | 是，尽力而为 |
| `CANCEL_REQUESTED` | 已收到取消命令，等待执行器确认 | 否，重复请求返回当前状态 |
| `SUCCEEDED` | 输出已持久化且任务完成 | 否 |
| `FAILED` | 任务因业务、参数、上游或系统错误结束 | 否 |
| `CANCELLED` | 任务未产生可用结果即被取消 | 否 |

`CANCEL_REQUESTED` 是目标逻辑状态。当前实体枚举暂未包含它，首期实现可在取消命令中写入取消请求时间/原因并由执行器确认后落为 `CANCELLED`；在数据库模型升级后应保留该中间状态，避免把“已请求取消”误显示为“已完成取消”。

### 5.2 状态迁移规则

- 只允许状态机中的正向迁移；终态 (`SUCCEEDED`、`FAILED`、`CANCELLED`) 不可被后台线程覆盖。
- 每次迁移使用乐观锁或带状态条件的更新，例如 `where id = ? and status in (...)`，防止取消和完成同时写入时出现回退。
- `RUNNING -> SUCCEEDED` 只有在输出文件和业务结果完成持久化后才允许发生。
- 取消请求先写入持久化状态，再通知队列/执行器；通知失败时由补偿扫描重试，不直接返回“取消失败”。
- 任务取消不自动取消已开始的兄弟任务。尚未运行且依赖于被取消任务的后续任务由调度器标记为 `CANCELLED`，原因使用 `DEPENDENCY_CANCELLED`。
- 用户取消不触发自动重试；超时、网络错误和上游 5xx 是否重试由执行器策略决定。

## 6. 领域模型

### 6.1 任务聚合（目标模型）

下面是逻辑字段，不代表本次直接修改实体或迁移表结构。

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `Long` | 与现有 `ProductionTask.id` 兼容的任务主键 |
| `project_id` | `Long` | 所属项目，所有查询和授权的必填边界 |
| `episode_id` | `Long?` | 所属剧集；项目级任务为空 |
| `project_version_id` | `Long?` | 提交时使用的版本快照 |
| `task_type` | 枚举 | 稳定任务类型 |
| `status` | 枚举 | 生命周期状态 |
| `progress` | `Int` | 0 到 100，未知进度时使用 0 并通过 `progress_indeterminate` 表示 |
| `stage` | `String?` | 执行器当前阶段，如 `DOWNLOAD_INPUT`、`CALL_PROVIDER`、`PERSIST_OUTPUT` |
| `title` | `String` | 脱敏后的可读标题 |
| `provider_config_id` | `Long?` | AI 提供商连接配置 ID，不返回密钥 |
| `model_name` | `String?` | 使用的模型名称 |
| `input_summary` | `String?` | 面向用户的摘要，不保存完整提示词 |
| `input_payload` | JSON | 执行所需结构化参数；敏感字段按白名单保存或哈希化 |
| `output_file_id` | `Long?` | 兼容当前单输出文件；多输出通过结果项扩展 |
| `output_file_ids` | `List<Long>` | 多媒体结果的逻辑字段 |
| `error_code` | `String?` | 稳定错误码 |
| `error_message` | `String?` | 脱敏、可展示的错误说明 |
| `cancel_requested_at` | `LocalDateTime?` | 取消请求时间 |
| `cancelled_by` | `Long?` | 发起取消的用户；系统取消使用系统主体 |
| `cancel_reason` | `String?` | 长度受限的原因或系统枚举 |
| `attempt` | `Int` | 当前执行尝试次数，从 1 开始 |
| `max_attempts` | `Int` | 执行器允许的最大尝试次数 |
| `idempotency_key` | `String?` | 防止重复提交的业务幂等键 |
| `correlation_id` | `String` | 贯穿任务、AI 调用、日志和审计的关联 ID |
| `parent_task_id` | `Long?` | 批量任务或派生任务的父任务 |
| `created_at` / `queued_at` / `started_at` / `completed_at` / `updated_at` | `LocalDateTime?` | 生命周期时间，使用 ISO-8601 传输 |
| `entity_version` | `Long` | 乐观锁版本 |

当前 `ProductionTask` 中已有的大部分字段直接作为首期读取来源。`input_payload` 只能由执行器按白名单解释；不能把任意 JSON 当作上游请求体直接转发。

### 6.2 任务事件

任务详情需要可诊断的时间线，建议增加逻辑上的 `TaskEvent`（实际持久化方式另行评审）：

```text
event_id, task_id, event_type, from_status, to_status,
stage, progress, message_code, actor_id, correlation_id, created_at
```

事件不保存 API Key、完整提示词、图片字节、原始上游响应或完整异常堆栈。事件类型至少包括 `CREATED`、`ENQUEUED`、`STARTED`、`PROGRESS`、`CANCEL_REQUESTED`、`CANCELLED`、`SUCCEEDED`、`FAILED`。

## 7. 后端架构

```text
业务入口（脚本 / 资产 / 视频）
                |
                v
       TaskCommandService
       /               \
  创建/取消             TaskDispatcher
                           |
                     有界队列 / Redis Stream
                           |
                    TaskWorker + Executor
                    /        |        \
             AI 调用服务   文件服务   业务结果服务
                           |
                    TaskQueryService
                           |
                  TaskCenterController
```

### 7.1 组件职责

| 组件 | 职责 |
| --- | --- |
| `TaskCommandService` | 创建任务、校验项目范围、写入快照、请求取消、幂等处理 |
| `TaskQueryService` | 分页查询、详情、统计、权限过滤、事件时间线 |
| `TaskDispatcher` | 将 `PENDING` 任务放入有界队列，处理重试和补偿扫描 |
| `TaskWorker` | 领取任务、心跳、执行阶段更新、响应取消令牌 |
| `TaskExecutor` | 面向单一 `task_type` 的实际业务执行器 |
| `CancellationRegistry` | Redis 中保存短 TTL 取消令牌，降低执行器读取数据库的压力 |
| `TaskEventPublisher` | 发布状态事件；首期供轮询和日志使用，后续适配 SSE/WebSocket |
| `TaskAuthorization` | 复用项目成员/所有权和系统权限进行读写判断 |
| `TaskRetentionJob` | 按保留策略清理历史输入摘要和事件，不能删除合规要求保留的统计审计 |

### 7.2 事务与网络调用

任何数据库事务都不能跨越 AI 或文件网络调用。推荐执行步骤：

1. 短事务：校验权限和输入，创建 `PENDING` 任务及调用快照。
2. 短事务：状态改为 `QUEUED`，写入 `ENQUEUED` 事件。
3. 队列领取后短事务：状态改为 `RUNNING`，写入尝试编号、执行器标识和 `started_at`。
4. 无事务网络调用：调用 AI 客户端、下载临时结果、写入文件服务；期间定期发送心跳和进度。
5. 新短事务：持久化业务结果和输出文件引用，最后将任务改为 `SUCCEEDED`；失败或取消也在独立短事务中落终态。

AI 调用必须复用 `correlation_id`/`invocation_id`，重试增加 `attempt` 而不是创建没有关联关系的新任务。

### 7.3 队列与并发

- 首期可使用 Redis Stream 或基于 Redis 的有界队列；同一任务只能被一个有效 worker lease 领取。
- 队列必须有上限。队列满时创建接口返回 `429`，并在响应中提供 `Retry-After`，不能无限积压。
- 并发限制按提供商配置、任务类型和项目分别设置。建议默认：图像类 2、对话/解析 8、合成 1，可配置。
- worker 持有租约并每 15 秒更新心跳；超过租约时间的 `RUNNING` 任务由补偿器标记为可恢复失败或重新入队，避免双重执行。
- 对可能产生计费的图像和合成调用默认不自动重试，除非执行器确认请求幂等。

## 8. HTTP 接口设计

所有接口使用项目统一 `Response` 包装和 `ResponseBuilder`。控制器方法直接声明 `@PathVariable`、`@RequestParam`、`@RequestHeader` 等输入，线上的 JSON 字段使用 snake_case。

### 8.1 查询任务列表

```http
GET /api/projects/{project_id}/tasks
```

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `episode_id` | 否 | 仅查看某剧集 |
| `status` | 否 | 可重复，取 `PENDING`、`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED` |
| `task_type` | 否 | 可重复的任务类型 |
| `created_from` / `created_to` | 否 | ISO-8601 `LocalDateTime` |
| `page` | 否 | 从 0 开始，默认 0 |
| `page_size` | 否 | 默认 20，最大 100 |
| `sort` | 否 | 默认 `created_at_desc`；不接受任意字段 |

响应 `data`：

```json
{
  "list": [
    {
      "id": 1024,
      "project_id": 12,
      "episode_id": 3,
      "task_type": "IMAGE_GENERATION",
      "title": "第 3 集分镜画面生成",
      "status": "RUNNING",
      "progress": 46,
      "progress_indeterminate": false,
      "stage": "CALL_PROVIDER",
      "model_name": "image-model-1",
      "created_at": "2026-08-31T14:20:00",
      "started_at": "2026-08-31T14:20:05",
      "completed_at": null,
      "error_code": null,
      "error_message": null,
      "can_cancel": true
    }
  ],
  "page": 0,
  "page_size": 20,
  "total": 1,
  "counts": {
    "PENDING": 0,
    "QUEUED": 0,
    "RUNNING": 1,
    "SUCCEEDED": 0,
    "FAILED": 0,
    "CANCELLED": 0
  }
}
```

列表不返回 `input_payload`、提供商密钥、完整错误体或未签名的文件 URL。`counts` 必须遵守相同的项目/剧集权限边界。

### 8.2 查询任务详情

```http
GET /api/projects/{project_id}/tasks/{task_id}
```

详情响应在列表字段基础上增加：

- `input_summary`、`provider_config_id`（仅 ID）、`attempt`、`parent_task_id`；
- `output_files`，通过现有文件服务按当前用户权限重新签发短期下载链接；
- `dependencies`，只返回任务 ID、类型和状态；
- `events`，按时间正序返回脱敏时间线；
- `cancel_requested_at`、`cancelled_by`、`cancel_reason`。

不存在任务或任务不属于项目时统一返回 `404`，避免泄露跨项目任务存在性。

### 8.3 取消任务

```http
POST /api/projects/{project_id}/tasks/{task_id}/cancel
Idempotency-Key: optional-client-key
```

可选查询参数 `reason`，长度限制 200 字符；不接收请求体 DTO。服务端记录当前用户作为 `cancelled_by`。

行为约定：

1. `PENDING`/`QUEUED`：原子更新为 `CANCELLED`，同时从队列删除或让领取时跳过。
2. `RUNNING`：原子更新为 `CANCEL_REQUESTED`（兼容期可先记录取消字段），写入 Redis 取消令牌；worker 终止可中断操作后确认 `CANCELLED`。
3. `SUCCEEDED`/`FAILED`/`CANCELLED`：不改变状态，返回当前任务，保证重复请求幂等。
4. 取消与成功同时到达时，以“结果是否已经持久化”为准：结果已持久化则保持 `SUCCEEDED`，否则任务最终为 `CANCELLED` 且不保留半成品引用。
5. 如果上游请求无法中断，worker 必须丢弃其返回结果，不创建媒体文件，并在取消事件中记录 `upstream_abort_deferred`。

成功响应使用 `200` 返回任务当前状态；任务已进入终态但不能取消时仍返回 `200` 和 `can_cancel: false`，不把幂等操作当成错误。任务不存在返回 `404`，无权限返回 `403`。

### 8.4 后续事件推送（非首期必需）

保留以下事件主题和载荷形状：

```text
task.{project_id}.{task_id}
{
  "task_id": 1024,
  "status": "RUNNING",
  "progress": 46,
  "stage": "CALL_PROVIDER",
  "updated_at": "2026-08-31T14:21:10"
}
```

首期前端每 4 秒刷新活动任务列表；后续可将相同载荷适配为 SSE 或 WebSocket，不改变页面数据模型。

## 9. 权限与安全

- 任务读取至少要求当前用户对 `project_id` 具有项目成员/所有者权限；取消需要项目编辑或制作权限。管理员只有在具备明确系统权限时才能跨项目操作。
- 建议增加细粒度权限码 `task:read`、`task:cancel`，并与现有 `AccessControlService` 的角色体系对齐。项目级授权必须在服务层再次校验，不能只依赖 URL 中的项目 ID。
- 查询、详情和取消都要验证任务的 `project_id` 与路径项目一致，不能通过猜测 `task_id` 访问其他项目。
- API Key、代理凭据、完整提示词、图片字节、base64 数据和完整上游响应禁止写入任务表、事件表、日志和前端响应。
- `error_message` 只允许来自受控错误映射；上游原始错误保存状态码、错误码和安全摘要。
- 文件输出通过 `FileService` 重新签发短期链接；任务中心不直接返回对象存储路径或供应商临时 URL。
- 取消操作写入审计：操作者、任务、原状态、目标状态、时间、原因长度/哈希和关联 ID。审计不能记录取消请求中的敏感文本。
- 生产环境需要确认当前安全配置不会把 `/api/**` 全部放行；任务接口上线前必须具备认证和项目授权测试。

## 10. 前端任务中心

### 10.1 入口与布局

1. 项目工作区顶部提供任务中心入口，显示活动任务数量（`PENDING` + `QUEUED` + `RUNNING` + `CANCEL_REQUESTED`）。
2. `/projects/{project_id}/tasks` 作为完整页面；工作流页面只显示最近 3 条活动任务和“查看全部”入口。
3. 桌面端使用“筛选栏 + 任务表格 + 详情抽屉”；移动端将每行折叠为任务卡片，操作按钮保持可触达。

### 10.2 列表字段与交互

列表列：任务类型/标题、项目或剧集上下文、状态、进度/阶段、模型、创建时间、耗时、操作。

筛选：状态分段控件、任务类型菜单、剧集菜单、时间范围和“只看活动任务”。默认按创建时间倒序，活动任务置顶仅作为可选视图，不改变服务端排序契约。

操作：

- 活动任务显示取消图标按钮，悬停提供明确 tooltip；点击后弹出确认框，展示任务标题和“取消后不会生成输出”的提示。
- `CANCEL_REQUESTED` 显示“取消中”，禁用重复按钮；下次刷新确认最终状态。
- 终态任务只显示详情、打开输出或查看错误，不显示取消。
- 失败信息分为用户可读摘要和可复制的错误编号，禁止把堆栈或上游原文放到界面。

### 10.3 数据刷新与异常状态

- 首期对活动任务轮询，间隔 4 秒；页面不可见时暂停，连续失败使用 4/8/16 秒退避，恢复后回到 4 秒。
- 取消成功后立即更新本地行状态为 `CANCEL_REQUESTED`，避免用户重复点击；服务端响应是最终依据。
- 首次加载、空列表、网络错误、无权限和任务已不存在都提供独立状态。刷新失败不能清空已有任务数据。
- 新增或修改的 Nuxt/Vue/composable/store/type 文件必须使用 TypeScript；任务状态和响应类型集中放在 `frontend/app/types`，避免页面各自声明不一致结构。

## 11. 一致性、幂等与竞态处理

### 11.1 创建幂等

业务入口为一次用户操作生成 `idempotency_key`，格式由调用方决定但长度受限。相同项目、任务类型、业务关联和幂等键在有效期内只返回同一任务；参数不同返回 `409`。`VideoWorkspaceService.queue` 批量创建时应为整批生成父关联 ID，并确保重复点击不会产生第二批活动任务。

### 11.2 取消竞态

取消命令与 worker 完成命令都必须带预期状态和实体版本。数据库只接受第一个满足条件的更新，失败方重新读取任务再决定返回当前状态。Redis 取消令牌不是事实来源，数据库终态才是事实来源。

### 11.3 进度语义

- 进度只能单调不减，除非任务重新开始新的 `attempt`。
- 执行器无法估算进度时返回 `progress_indeterminate: true`，不要伪造百分比。
- 任务中心不根据子任务数量简单平均进度；父任务应由编排器按权重汇总。

## 12. 错误码与可观测性

建议稳定错误码：

| 错误码 | 含义 | HTTP |
| --- | --- | --- |
| `TASK_NOT_FOUND` | 任务不存在或不属于项目 | 404 |
| `TASK_CANCEL_NOT_ALLOWED` | 任务已进入不可取消阶段 | 409（仅非幂等冲突场景） |
| `TASK_QUEUE_FULL` | 有界队列已满 | 429 |
| `TASK_DEPENDENCY_BLOCKED` | 依赖任务失败或取消 | 409 |
| `TASK_EXECUTOR_UNAVAILABLE` | 当前类型执行器不可用 | 503 |
| `AI_PROVIDER_ERROR` | AI 配置、认证、限流或上游错误 | 按 AI 客户端错误映射 |
| `TASK_OUTPUT_PERSIST_FAILED` | 结果生成但未能安全持久化 | 503/500，任务为 `FAILED` |

指标至少包括：活动任务数、各状态迁移数、排队等待时长、执行时长、取消请求到确认时长、取消成功率、worker 租约超时数、队列深度、按任务类型/提供商的成功率和失败码。模型名称只能作为受控低基数标签。

结构化日志必须包含 `task_id`、`project_id`、`task_type`、`status`、`attempt`、`correlation_id`；不得包含提示词正文和密钥。

## 13. 数据保留与清理

- 任务元数据默认保留 30 天，成功任务的输出文件按项目资产生命周期保留；实际期限由产品和合规评审确定。
- 终态任务只读，不能由用户删除。过期任务可归档，归档前保留不可逆统计和必要审计。
- 清理任务不得删除仍被资产、分镜或项目版本引用的输出文件；文件删除必须经过 `FileService` 的归属和引用检查。
- 清理和归档作为后台作业运行，使用批量分页，不能长事务锁住整个项目。

## 14. 测试策略与验收标准

### 14.1 后端

- 状态机单元测试：覆盖所有合法迁移和非法回退。
- 服务测试：跨项目访问、项目成员权限、重复取消、终态取消、参数不一致的幂等键。
- 并发测试：取消与成功、取消与 worker 领取、两个 worker 同时领取同一任务。
- 队列测试：队列满、租约超时、补偿重入、依赖任务取消传播。
- AI/文件集成测试：上游超时、429、临时 URL 下载失败、取消后不得产生输出文件。
- Controller 测试：snake_case 查询参数、统一 `Response` 包装、`404/403/409/429/503` 映射。

### 14.2 前端

- 列表筛选、分页、活动任务轮询暂停/恢复和错误退避。
- 取消确认、取消中禁用、重复点击、任务在确认前已完成等竞态。
- 桌面/移动布局下的长标题、长错误摘要和空列表状态。
- 输出文件链接过期时提示重新加载，不把后端敏感错误原样显示。

### 14.3 验收条件

1. 用户可以在项目范围内看到与工作流页面一致的任务状态。
2. 对排队任务取消后，worker 不会执行该任务；对运行中任务取消后，不会产生可用输出。
3. 重复取消请求不会创建新任务、不会重复扣费、不会改变终态。
4. 任何跨项目任务 ID 猜测都只得到 `404`。
5. 任务列表在活动任务量增加时仍受分页和队列上限保护。
6. 日志、接口和任务详情均不泄露 API Key、完整提示词和原始上游响应。

## 15. 分阶段实施计划

### 阶段 0：评审与契约冻结

- 评审状态机、任务类型注册表、权限码和错误码。
- 确认项目成员授权模型以及 `CANCEL_REQUESTED` 的持久化方案。
- 完成数据库逻辑模型和索引评审；本阶段不提交迁移脚本。

### 阶段 1：只读任务中心

- 新增 `TaskQueryService` 和任务中心控制器，先适配现有 `ProductionTaskRepository`。
- 提供列表、详情、统计和前端页面，使用轮询展示当前已有制作任务。
- 将 `VideoWorkspaceController` 中重复的任务展示字段逐步对齐任务中心字段。

### 阶段 2：取消闭环

- 引入 `TaskCommandService`、取消令牌和 worker 领取协议。
- 将 `VideoWorkspaceService.queue` 改为调用任务命令服务，保证批量创建幂等。
- 完成排队取消、执行中尽力取消、输出丢弃和并发竞态测试。

### 阶段 3：接入执行器

- 先接入视频制作的图片、配音、音乐、字幕和合成执行器。
- 再接入 AI 图像生成/编辑、剧本解析和资产提取，统一使用 `AiInvocationService` 的关联 ID 和错误映射。
- 增加任务事件、指标、补偿扫描和租约恢复。

### 阶段 4：实时体验与治理

- 以同一事件载荷增加 SSE/WebSocket，保留轮询降级。
- 增加管理员任务诊断视图、保留策略、归档和队列容量配置。
- 评估是否将 `ProductionTask` 与其他异步任务迁移到统一任务表；迁移需单独立项。

## 16. 待确认问题

1. 项目成员的角色和权限目前由哪一层数据模型承载，普通成员是否允许取消他人创建的任务？
    普通成员只能管理自己的任务
2. 任务中心是只提供项目内入口，还是需要额外提供“我的全部任务”跨项目视图？
   需要额外提供“我的全部任务”跨项目视图
3. 取消运行中任务时，产品是否允许供应商继续计费但丢弃结果，还是需要在 UI 明确显示“可能已产生上游费用”？
   需要额外提供“我的全部任务”跨项目视图并且强行打断请求
4. 成功任务输出是单文件为主，还是首期就需要多文件结果集合？
   单文件为主
5. 任务和事件的保留天数、归档方式及审计合规要求是什么？
    暂时不考虑
6. 首期队列选择 Redis Stream 还是已有基础设施提供的消息队列？
   选择Redis Stream
    

