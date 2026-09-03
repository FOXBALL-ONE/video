# 剧本导入与 AI 资产解析流程

## 1. 文档目的

本文完善“剧本导入页面 -> 选择解析范围和 AI 提供商 -> 异步解析 -> 用户确认 -> 写入剧集资产库”的完整流程，作为前后端联调、实现和验收依据。

本文只描述业务流程和接口契约，不创建或修改数据库迁移脚本。实现时应复用项目现有的 ProductionTask、ScriptVersion、EpisodeAsset、TaskCenterService 和 ResponseBuilder。

### 1.1 本期决策

| 决策 | 约束 |
| --- | --- |
| 适用范围 | 本流程只处理已选择剧集的剧本；`episode_id` 必填。项目级剧本仍可使用既有同步解析接口，但不能通过本流程写入剧集资产库。 |
| 任务粒度 | 一次提交创建一个 `SCRIPT_ANALYSIS` 父任务；已勾选的资产类型是该任务内部的独立解析单元，而不是自动入库单元。 |
| 写入时机 | Worker 只生成并暂存结果。只有采纳命令可以创建或更新 `EpisodeAsset`。 |
| 版本保护 | 采纳校验 `version_id` 和提交时保存的 `content_hash`；不能只校验 `version_id`。当前草稿版本允许原地保存，ID 不一定变化。 |
| 线上状态 | 解析接口统一返回 `ProductionTaskStatus` 的机器值，成功终态为 `SUCCEEDED`，不再对新页面返回 `COMPLETED`。 |

## 2. 现状与目标行为

### 2.1 当前已有能力

- 剧本页面支持粘贴或上传文本，并通过 PUT /api/projects/{project_id}/script 保存当前剧本版本。
- 页面已提供解析提供商和解析模型选择，并在点击“开始解析”后打开资产提取弹窗。
- 弹窗可分别勾选 SCENE_BACKGROUND（场景）、CHARACTER（角色）和 PROP（道具），每种类型可选择自己的提供商和模型。
- 页面调用 POST /api/projects/{project_id}/script/analysis-tasks 创建异步任务，并轮询 GET /api/projects/{project_id}/script/analysis-tasks/{task_id}。
- 后端使用 ProductionTaskType.SCRIPT_ANALYSIS 保存任务，使用 AiInvocationService 调用已配置的 AI 提供商。
- ScriptVersion 会保存 AI 原始 JSON 和解析状态；现有解析服务还会直接更新剧本结构化数据和剧集资产。
- 当前 `PUT /script` 在版本仍为 `DRAFT` 时会原地更新内容，因此仅保存 `version_id` 无法识别同一版本 ID 下的剧本内容变更。
- 当前异步 Worker 先调用 `ScriptService.analyze`，再调用 `AssetService.extractEpisodeAssets`；这两个服务路径都可能写入业务数据，且其返回结果不能准确代表每个所选资产类型的独立任务结果。

### 2.2 本流程的目标行为

解析成功只代表“结果已生成并可预览”，不代表用户已经同意写入资产库。资产写入必须经过明确的“采纳解析结果”操作：

1. 用户完成剧本导入并保存。
2. 用户在已选择剧集的前提下，选择解析角色、场景、道具中的一个或多个，并为每类选择提供商和模型。
3. 后端创建一个可追踪的异步解析任务。
4. Worker 调用对应 AI 提供商，结果只写入任务结果暂存区，不修改正式资产库。
5. 任务成功后前端弹出结果确认小窗，展示各类数量、名称和简介。
6. 用户点击“采纳”后，后端以幂等方式将所选结果合并到当前剧集资产库，并返回新增/更新统计。
7. 用户点击“暂不采纳”或关闭弹窗时，保留任务结果供查看，不写入资产库。

## 3. 用户流程

~~~mermaid
sequenceDiagram
    actor U as 用户
    participant F as 剧本导入页
    participant API as Video 后端
    participant Q as 任务队列/Worker
    participant AI as AI 提供商
    participant DB as 任务与资产库

    U->>F: 填充或上传剧本
    U->>F: 选择默认提供商和模型
    U->>F: 点击“开始解析”
    F->>API: PUT /script 保存当前剧本版本
    API-->>F: 保存成功
    F->>U: 打开资产选择弹窗
    U->>F: 勾选角色/场景/道具并选择各自提供商、模型
    F->>API: POST /script/analysis-tasks
    API->>DB: 创建 QUEUED 任务和输入快照
    API-->>F: 返回 task_id
    API->>Q: 投递任务
    loop 直到终态
        F->>API: GET /script/analysis-tasks/{task_id}
        API-->>F: 状态、进度和错误/结果摘要
    end
    Q->>AI: 按资产类型调用对应提供商和模型
    AI-->>Q: 结构化 JSON 结果
    Q->>DB: 保存暂存结果，任务 SUCCEEDED
    F->>U: 弹出解析结果确认小窗
    alt 用户采纳
        U->>F: 点击“采纳并写入资产库”
        F->>API: POST /script/analysis-tasks/{task_id}/adopt
        API->>DB: 校验版本和权限，幂等合并 EpisodeAsset
        DB-->>API: 新增/更新统计
        API-->>F: 采纳成功
        F->>F: 刷新剧本预览和资产库
    else 用户暂不采纳
        U->>F: 点击“暂不采纳”或关闭
        F->>API: 可选 POST /.../discard
        API->>DB: 记录用户决定，不改资产库
    end
~~~

### 3.1 页面交互状态

| 状态 | 页面行为 |
| --- | --- |
| EDITING | 允许编辑剧本；“开始解析”要求正文非空。 |
| SAVING | 先保存剧本版本，按钮禁用并显示保存中。 |
| CONFIGURING | 打开弹窗，显示三类资产开关及其提供商/模型。至少选一类。 |
| QUEUED | 显示排队中；允许关闭弹窗，任务仍继续。配置选择不可再修改。 |
| RUNNING | 显示阶段和进度；允许取消任务，取消是尽力而为。 |
| SUCCEEDED | 关闭进度弹窗，打开结果确认小窗；此时结果已经暂存但不能自动改资产库。 |
| FAILED | 展示脱敏错误、提供商和请求 ID；允许重新提交新任务。 |
| CANCELLED | 提示任务已取消，不展示采纳按钮。 |
| ADOPTING | 采纳请求进行中，禁止重复点击。 |
| ADOPTED | 展示新增/更新数量并刷新剧集资产库。该状态来自结果采纳记录，不是 `ProductionTaskStatus`。 |
| DISCARDED | 保留结果查看记录，但不创建或更新资产。该状态来自结果采纳记录，不是 `ProductionTaskStatus`。 |

## 4. 解析弹窗设计

### 4.1 三类资产选项

| wire 值 | 中文名称 | 建议提取字段 |
| --- | --- | --- |
| CHARACTER | 角色 | name、identity、appearance、personality、relationships、dialogue_intent、remarks |
| SCENE_BACKGROUND | 场景 | name、location、environment_type、time、weather、lighting、visual_style、plot_purpose |
| PROP | 道具 | name、category、description、owner、function、continuity_notes、remarks |

角色、场景、道具是独立开关。未勾选的类型不得调用 AI，也不得在采纳时写入资产库。服装等其他类型不属于本流程，除非后续扩展 asset_types 白名单。

### 4.2 提供商和模型选择

- 默认提供商/模型来自页面顶部选择；打开弹窗时填充到每个已勾选项。
- 每个资产项都允许单独修改提供商和模型，不能假设三类任务必须使用同一个模型。
- 提供商下拉只显示启用的配置；模型列表通过现有 /api/ai/model-providers/models 获取。
- 提交前校验：必须已选剧集；至少勾选一类；每个已勾选项的 provider_config_id > 0 且 model 非空；剧本版本仍存在且正文非空。未选剧集时“开始解析”按钮应禁用并提示选择剧集，而不是先保存项目级剧本再打开无效弹窗。
- 前端不要提交 API Key；后端只接收提供商配置 ID，由 AiProviderResolver 在服务端解析密钥和连接策略。

## 5. 后端接口契约

所有响应使用项目统一 Response 包装。请求参数使用 snake_case；控制器方法直接声明 @PathVariable、@RequestParam 等参数，不新增请求包装 DTO。

### 5.1 保存剧本

~~~http
PUT /api/projects/{project_id}/script?episode_id={episode_id}
Content-Type: text/plain; charset=UTF-8

剧本文本
~~~

保存成功后返回当前 document_id、version、正文和统计信息。提交任务前，前端以保存后的正文计算 SHA-256 并作为 `content_hash` 提交；服务端用实际读取的正文重新计算并持久化该摘要。保存会使旧解析结果失效，因此采纳时必须校验当前版本 ID 和当前正文摘要均与任务快照一致。

### 5.2 提交解析任务

~~~http
POST /api/projects/{project_id}/script/analysis-tasks
Content-Type: multipart/form-data
Idempotency-Key: optional-client-key

episode_id=3
version_id=88
content_hash=sha256:...
character_provider_config_id=12
character_model=chat-model-default
scene_background_provider_config_id=18
scene_background_model=scene-model-v2
~~~

`episode_id`、`version_id` 和 `content_hash` 为本流程必填参数。解析类型由对应的 `*_provider_config_id` 与 `*_model` 成对出现决定：角色为 `character_*`，场景为 `scene_background_*`，道具为 `prop_*`。任一类型只提供其中一个参数时返回 400；三对参数都未提供时返回 400。

该命名参数形式符合控制器直接声明 HTTP 输入的约定，并避免当前 `asset_types`、`provider_config_ids`、`models` 三个数组依赖下标对齐的问题。现有数组参数可在前端切换完成前短期兼容，但服务端不得允许新旧两种参数混用；兼容层必须将数组转换为同一份受校验的领域选择项。

推荐响应：

~~~json
{
  "task_id": "1024",
  "status": "QUEUED",
  "asset_types": ["CHARACTER", "SCENE_BACKGROUND"],
  "version_id": 88,
  "correlation_id": "c6f2..."
}
~~~

重复提交处理：相同 Idempotency-Key、项目、剧集、版本、content_hash 和选择项应返回原任务；没有幂等键时允许创建新任务，但前端必须防止按钮重复点击。服务端应拒绝与当前正文摘要不一致的提交，防止客户端使用保存前内容的配置创建任务。

### 5.3 查询解析任务

~~~http
GET /api/projects/{project_id}/script/analysis-tasks/{task_id}
~~~

处理中响应只返回状态和进度；成功响应返回暂存结果，失败响应返回脱敏错误：

~~~json
{
  "task_id": "1024",
  "status": "SUCCEEDED",
  "progress": 100,
  "stage": "RESULT_STAGED",
  "result_status": "STAGED",
  "asset_types": ["CHARACTER", "SCENE_BACKGROUND"],
  "type_results": [
    {"type": "CHARACTER", "status": "SUCCEEDED", "provider_config_id": 12, "model": "chat-model-default", "count": 1},
    {"type": "SCENE_BACKGROUND", "status": "SUCCEEDED", "provider_config_id": 18, "model": "scene-model-v2", "count": 1}
  ],
  "version_id": 88,
  "result": {
    "characters": [{"id": "character_001", "name": "林岚", "identity": "记者", "appearance": "..."}],
    "scenes": [{"id": "scene_001", "name": "旧车站", "location": "城郊车站", "time": "夜"}],
    "props": []
  },
  "stats": {"character_count": 1, "scene_count": 1, "prop_count": 0},
  "created_at": "2026-09-01T14:20:00",
  "completed_at": "2026-09-01T14:20:18"
}
~~~

result 只包含本任务选择的资产类型。不能返回 API Key、完整提示词、未脱敏的上游响应或内部堆栈。时间字段使用 LocalDateTime 的 ISO-8601 无时区格式。

### 5.4 采纳解析结果

~~~http
POST /api/projects/{project_id}/script/analysis-tasks/{task_id}/adopt
Idempotency-Key: optional-client-key

selected_asset_types=CHARACTER
selected_asset_types=SCENE_BACKGROUND
version_id=88
~~~

采纳请求可只采纳成功结果中的部分类型；若不传 selected_asset_types，默认采纳任务成功时的全部类型。除 `version_id` 外，服务端必须从任务快照读取并验证 `content_hash`，防止用户在同一草稿版本 ID 下修改剧本后误把旧结果写入新内容。

响应示例：

~~~json
{
  "task_id": "1024",
  "task_status": "SUCCEEDED",
  "adoption_status": "ADOPTED",
  "version_id": 88,
  "selected_asset_types": ["CHARACTER", "SCENE_BACKGROUND"],
  "created_count": 2,
  "updated_count": 0,
  "skipped_count": 0,
  "asset_ids": [301, 302],
  "adopted_at": "2026-09-01T14:21:02"
}
~~~

采纳接口必须幂等：服务端以任务 ID、version_id、content_hash、已选类型集合和 Idempotency-Key 绑定一次采纳请求；同一请求重复调用不得重复创建资产，返回第一次采纳的结果。任务不是 SUCCEEDED、项目/剧集不匹配、版本已变化或用户无编辑权限时返回明确的 400/404/409。

### 5.5 放弃结果（可选）

~~~http
POST /api/projects/{project_id}/script/analysis-tasks/{task_id}/discard
~~~

该接口只记录用户决定和时间，不删除任务结果，不修改剧本和资产库。若产品不需要审计“放弃”动作，也可以只在前端关闭确认小窗；但同一任务仍不得再次显示意外写入入口。

## 6. 后端执行与数据边界

### 6.1 任务创建

1. 校验项目、非空 `episode_id`、剧本文档和当前版本。
2. 计算服务端 `content_hash`，并校验其与请求参数、当前版本 ID 一致。
3. 将 version_id、content_hash、正文摘要、asset_types、每类 provider_config_id/model 写入 ProductionTask.inputPayload；不保存密钥或正文全文。
4. 创建 SCRIPT_ANALYSIS 任务，状态为 QUEUED，记录 correlation_id，写入 ENQUEUED 事件。
5. 将任务投递到有界队列；队列满时返回 429 和 Retry-After，不能无限积压。

### 6.2 Worker 执行

任务执行必须遵守“数据库短事务与网络调用分离”：

1. 短事务：QUEUED -> RUNNING，记录 started_at、尝试次数和阶段。
2. 无事务调用 AI。每类资产使用自己的提供商配置和模型，沿用同一个任务 correlation_id，每次上游调用生成独立 request_id。
3. `ScriptAnalysisExecutor` 只调用纯解析组件，返回经字段白名单校验的 `AnalysisTypeResult`；不得调用 `ScriptService.analyze` 或 `AssetService.extractEpisodeAssets`，因为这两个现有服务会改变剧本结构或直接保存 `EpisodeAsset`。
4. 每个已选择类型独立记录结果：成功时保存该类型的 JSON 和 `result_hash`，失败时保存该类型的稳定错误码、脱敏信息、提供商和 request_id。
5. 全部类型失败时，任务进入 FAILED；至少一个类型成功时，任务进入 SUCCEEDED，`stage` 分别为 RESULT_STAGED 或 PARTIAL_RESULT_STAGED。前端只允许采纳成功暂存的类型，并同时展示失败类型的错误。
6. 只有采纳接口可以调用资产合并逻辑。Worker 不得调用会直接写入 EpisodeAsset 的旧提取路径。

### 6.3 结果暂存

`ProductionTask` 当前只有 `inputPayload` 和单个 `outputFileId`，并没有可承载结构化解析结果的输出 JSON 字段；`ScriptVersion.aiAnalysisJson` 也是“一个版本一份结果”，无法在并发任务下保证任务归属。因此两者都不能作为采纳的唯一事实来源。

目标模型应引入逻辑上的 `ScriptAnalysisResult` 聚合及其类型结果项。具体表结构和迁移不在本文范围内，必须在数据模型评审时落地；在此之前，不得以覆盖 `ScriptVersion.aiAnalysisJson` 或回写 `inputPayload` 代替持久化结果。

| 字段 | 说明 |
| --- | --- |
| id / task_id | 结果主键；`task_id` 唯一关联 `SCRIPT_ANALYSIS` 任务。 |
| project_id / episode_id | 与任务相同的不可变权限边界。 |
| version_id / content_hash | 提交时的剧本快照，用于采纳前比较。 |
| result_status | `STAGED`、`PARTIALLY_ADOPTED`、`ADOPTED`、`DISCARDED`、`SUPERSEDED`。 |
| type_results | 每类的类型、提供商配置、模型、状态、result_json、result_hash、error_code、request_id。 |
| adoption_records | 每次采纳的类型集合、幂等键、操作者、时间、创建/更新/跳过数量及资产 ID。 |
| created_at / updated_at | 使用 LocalDateTime，按 ISO-8601 传输。 |

单任务每种资产类型只能有一个类型结果项。相同类型的重试覆盖该类型的暂存值前，应保留旧 result_hash 和 request_id 审计事件。结果 JSON 设置大小上限，写入前按白名单规范化；原始上游响应只允许按脱敏和保留策略保存。

## 7. 采纳入库规则

### 7.1 通用规则

- 采纳时重新校验项目成员权限、剧集归属、version_id 和 content_hash。当前剧本内容任一变化均返回 409，不写入任何资产。
- 采纳操作在一个短事务内完成，不在事务中调用 AI。先原子取得该类型集合的采纳权，再执行合并；异常必须回滚资产和采纳记录。
- 以“当前剧集 + 规范化名称 + 资产类型”作为默认冲突键；名称先 trim，再做 Unicode 兼容规范化和大小写规范化。
- `EpisodeAsset` 当前没有来源任务或字段所有权信息。首期安全策略是“只创建，不覆盖”：冲突键已存在时不更新 description、notes、reference_url 或 status，记为 `skipped_count` 并返回 `ASSET_NAME_CONFLICT`。
- 不存在资产执行新增，初始状态为 DRAFT；不得因为 AI 结果直接标记为 READY 或 LOCKED。
- 结果中缺少 name 的条目计入 skipped_count 并记录 `MISSING_NAME`，不阻塞其他合法条目。
- 只处理用户选择且类型结果为 SUCCEEDED 的项；结果中出现未选择类型时忽略并记录审计信息。
- 后续若增加 `source_task_id`、字段来源和人工覆盖标识，可允许同一来源、未人工修改且仍为 DRAFT 的资产更新；该扩展不能改变首期的“不覆盖现有资产”语义。

### 7.2 角色

写入 EpisodeAssetType.CHARACTER：

- name <- name
- description <- 身份、外貌、性格的摘要
- notes <- 关系、对白意图、备注等完整结构化信息

如项目后续需要跨剧集角色库，另行设计项目级 Asset 与剧集 EpisodeAsset 的关联；本流程先保证当前剧集资产库可用。

### 7.3 场景

写入 EpisodeAssetType.SCENE_BACKGROUND。description 保存地点、时间、天气、光线和视觉风格摘要，完整字段放入受控备注，避免覆盖人工参考图和链接。

### 7.4 道具

写入 EpisodeAssetType.PROP。description 保存外观/材质，notes 保存类别、持有人、剧情功能和连续性要求。

### 7.5 剧本结构化数据

角色、场景和对白等 ScriptVersion 结构化数据的更新也应遵循采纳策略：

- 若“采纳”仅表示资产入库，则剧本结构化预览可以在任务成功时更新，但必须明确告知用户这不是资产库写入。
- 若产品要求“所有 AI 解析结果都需确认”，则 replaceStructuredDataFromAnalysis 也必须延迟到采纳接口执行。
- 两种策略不能混用；前端文案和接口状态必须保持一致。推荐首期采用“结果成功即更新只读预览，资产库需采纳”的方案，降低用户等待成本。

## 8. 状态、取消与并发

### 8.1 状态映射

执行任务和结果采纳是两个独立状态机，不能把 `ADOPTED`、`DISCARDED` 写进 `ProductionTask.status`：

| 维度 | 状态 | 语义 |
| --- | --- | --- |
| 任务执行 | `PENDING`、`QUEUED`、`RUNNING`、`CANCEL_REQUESTED`、`SUCCEEDED`、`FAILED`、`CANCELLED` | AI 调用及结果暂存的生命周期。 |
| 解析结果 | `STAGED`、`PARTIALLY_ADOPTED`、`ADOPTED`、`DISCARDED`、`SUPERSEDED` | 用户对暂存结果的处理生命周期。 |
| 类型结果 | `SUCCEEDED`、`FAILED`、`CANCELLED` | 单个角色/场景/道具解析单元的结果，用于部分成功展示。 |

只有 `ProductionTaskStatus.SUCCEEDED` 且结果为 `STAGED` 或 `PARTIALLY_ADOPTED` 时允许采纳。旧脚本接口的 `COMPLETED` 仅可存在于兼容适配层；新页面和新接口只使用 `SUCCEEDED`。

### 8.2 取消

- PENDING/QUEUED：原子更新为 CANCELLED，Worker 领取时跳过。
- RUNNING：更新为 CANCEL_REQUESTED，通过 TaskCancellationRegistry 通知 Worker；Worker 完成可中断操作后确认 CANCELLED。
- 已 SUCCEEDED 的任务不能取消采纳结果；用户可以选择放弃结果。
- 取消与成功竞态时，以结果是否已经暂存并提交为准；资产采纳仍必须由用户显式触发。

### 8.3 并发采纳

使用结果记录的乐观锁或带状态条件的更新，保证同一任务、同一资产类型集合和同一快照只能被采纳一次。两个浏览器同时点击采纳时，一个请求执行合并，另一个返回同一幂等结果，不得产生重复资产。

部分采纳后结果状态为 `PARTIALLY_ADOPTED`；剩余成功类型仍可在同一 version_id/content_hash 下继续采纳。所有成功类型都被采纳后才进入 `ADOPTED`。剧本更新后，尚未完整采纳的结果进入 `SUPERSEDED`，采纳接口返回 409。

## 9. 前端轮询与确认小窗

### 9.1 轮询策略

- 提交成功后将 task_id、project_id、episode_id、version_id 和 content_hash 保存为页面可恢复状态，并写入当前路由查询参数或项目级持久化前端状态。重新进入页面时优先恢复该任务，再查询任务中心中的同剧集最近任务。
- 页面可见时每 1 秒查询一次；连续 30 次未完成后改为 2 秒，90 次后改为 5 秒。达到轮询上限只停止前台自动轮询并提示任务仍可在任务中心查看，不能误报 FAILED。
- 同一 task_id 在一个浏览器中只能有一个活动轮询器。页面离开、组件卸载、网络断开或开始新任务时必须取消旧轮询器。
- QUEUED 和 RUNNING 显示任务状态、当前阶段和进度；不要把“解析中”误显示为“已入库”。
- SUCCEEDED 时关闭任务配置弹窗，打开确认小窗并展示每类的状态、数量、提供商和模型。结果为空时仍允许用户查看原始摘要，但采纳按钮应禁用。
- 部分类型失败时仍显示成功类型并允许采纳；失败类型显示稳定错误码、提供商和 request_id，且不可勾选。
- FAILED 显示后端返回的 error_code、提供商和 request_id，不显示 API Key 或完整异常堆栈。

### 9.2 确认小窗按钮

| 按钮 | 行为 |
| --- | --- |
| 采纳并写入资产库 | 默认勾选尚未采纳且解析成功的类型；调用采纳接口后刷新 loadLibrary/剧集资产列表，展示新增、冲突跳过和无名称跳过数量。 |
| 暂不采纳 | 关闭小窗并保留任务结果；不修改资产库。 |
| 查看详情 | 展开完整的结构化结果，仍不直接写库。 |

采纳请求期间按钮禁用。部分采纳后小窗只允许选择剩余成功类型；全部采纳后显示“已采纳”，再次打开同一任务只能查看结果和采纳记录。

## 10. 错误处理

| 场景 | HTTP/状态 | 前端处理 |
| --- | --- | --- |
| 项目/剧集/剧本不存在 | 404 | 提示当前上下文已失效并返回项目或剧集页。 |
| 正文为空、未选资产、模型为空 | 400 | 在对应表单项附近提示，不创建任务。 |
| 未选择剧集或某类型参数不成对 | 400 | 提示选择剧集并补齐该类型的提供商和模型。 |
| AI 配置不存在或已停用 | 400/503 | 提示重新选择提供商，不暴露密钥信息。 |
| 上游鉴权、限流、5xx | 单类 FAILED 或任务 FAILED，携带稳定错误码 | 存在成功类型时允许只采纳成功项；全部失败时允许重新提交新任务。 |
| 返回非法 JSON | FAILED，错误码 INVALID_AI_JSON | 保留原任务供诊断，不写入资产库。 |
| 队列已满 | 429 | 使用 Retry-After 提示稍后重试。 |
| 任务被取消 | CANCELLED | 关闭采纳入口，保留取消记录。 |
| 版本或正文已变化后采纳 | 409 | 提示先重新解析当前剧本内容。 |
| 无编辑权限或任务不属于项目 | 404 | 不泄露跨项目任务存在性，禁止采纳。 |

## 11. 安全与审计

- 所有查询、取消、采纳都以项目和剧集为权限边界；任务不属于项目时统一按 404 处理，避免泄露任务存在性。
- 前端只传提供商配置 ID 和模型名，API Key 只在后端配置中读取。
- 日志和任务事件记录 task_id、correlation_id、request_id、状态和错误码，不记录 API Key、完整提示词、图片字节和未脱敏上游响应。
- 采纳事件至少记录任务、版本、资产类型、操作者、时间、创建/更新/跳过数量和结果哈希。
- 结果和备注设置长度上限；AI 返回内容按字段白名单解析，防止超大响应和任意 JSON 污染业务数据。

## 12. 验收标准

### 12.1 正常流程

- [ ] 保存剧本后点击“开始解析”会打开三类资产选择弹窗。
- [ ] 可以只选择角色、只选择场景、只选择道具，或任意组合。
- [ ] 每类资产可以使用不同的提供商和模型，提交后任务输入快照与页面选择一致。
- [ ] 任务经历 QUEUED -> RUNNING -> SUCCEEDED，页面能在刷新后继续查询。
- [ ] 任务成功只弹出结果确认小窗，未点击采纳前资产库无新增或更新。
- [ ] 点击采纳后，当前剧集资产库出现对应角色、场景和道具，且页面统计刷新。

### 12.2 边界与幂等

- [ ] 空剧本、未选资产、缺少模型、停用提供商不会创建任务。
- [ ] AI 返回非法 JSON、上游限流、鉴权失败会进入 FAILED，且不写资产库。
- [ ] 排队或运行中取消不会产生资产；运行中取消最终可观察到 CANCELLED。
- [ ] 重复点击提交不会产生重复任务（带幂等键时）；重复点击采纳不会产生重复资产。
- [ ] 旧任务结果不能写入新剧本版本；版本变化时采纳返回 409。
- [ ] 无权限用户不能查询或采纳其他项目任务。

- [ ] 同一 `DRAFT` 版本保存新正文后，旧任务的采纳因 content_hash 不一致返回 409。
- [ ] 同时提交命名参数和旧数组参数会返回 400；某类型只带提供商或模型也会返回 400。
- [ ] 三类中一类失败、其余成功时任务返回 SUCCEEDED，确认小窗只允许采纳成功类型。
- [ ] 两个浏览器同时采纳相同类型集合时，只创建一次资产和一条采纳记录。
- [ ] 同名同类型的既有资产不被 AI 结果覆盖，采纳结果正确计入 ASSET_NAME_CONFLICT。
- [ ] 页面刷新或从任务中心返回后，可恢复任务进度和暂存结果，不重复提交任务。

## 13. 实施顺序

1. 新增纯解析执行器和结果暂存聚合，使 Worker 不再调用会写入业务表的 `ScriptService.analyze`、`AssetService.extractEpisodeAssets`。
2. 落地任务结果查询和采纳命令；先完成版本/摘要校验、幂等记录与只创建冲突策略，再开放前端入口。
3. 前端切换到命名的类型参数，增加进度恢复、部分成功和结果确认小窗；旧数组参数在一个发布周期后移除。
4. 以第 12 节验收项覆盖端到端测试、并发采纳测试和权限测试后，再将任务入口对所有项目成员开放。

## 14. 与当前代码的落地差异

当前 ScriptAnalysisTaskService.execute 先调用 ScriptService.analyze，再调用 AssetService.extractEpisodeAssets；前者会更新剧本结构化数据并同步资产，后者会直接保存 EpisodeAsset。要满足本文目标，两条调用都必须从 Worker 中移除，改为纯解析、按类型暂存结果，并新增采纳命令服务/控制器入口。

当前前端提交时使用并行数组参数、轮询上限后报超时，并在任务完成后直接打开“解析结果概览”。应改为命名类型参数、可恢复的单轮询器和带“采纳并写入资产库/暂不采纳”的确认小窗；loadLibrary 和剧集资产列表刷新应放在采纳成功之后，同时保留结果查看能力。

当前任务查询接口返回的兼容状态包含 COMPLETED，而任务中心实体使用 SUCCEEDED。建议统一线上任务状态为 SUCCEEDED，仅在旧接口适配层转换，避免前端状态判断分裂。
