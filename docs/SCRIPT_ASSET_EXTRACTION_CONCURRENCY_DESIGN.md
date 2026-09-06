# 剧本资产并发提取设计

## 1. 文档目的

本文定义剧本资产提取从“按资产类型串行调用 AI”改为“按资产类型并发执行”的目标设计。首期将角色和场景拆为两个独立子任务；每个子任务可以使用不同的 AI 提供商、模型、提示词和输出契约。道具复用同一机制，作为后续可启用的第三类子任务。

本文是实现设计，不创建或修改数据库迁移脚本。实现须复用现有 `ProductionTask`、`ScriptAnalysisResult`、`TaskCenterService`、`ScriptAssetAnalysisService`、提示词目录和 `ResponseBuilder`。

关联文档：

- `SCRIPT_IMPORT_ANALYSIS_FLOW.md`：剧本导入、结果暂存和采纳规则。
- `AI_PROMPT_MANAGEMENT_DESIGN.md`：提示词版本、审计和渲染规则。
- `TASK_CENTER_DESIGN.md`：异步任务状态、取消和审计约束。

## 1.1 迭代记录

| 版本 | 优化重点 | 结论 |
| --- | --- | --- |
| v1 | 初版并发拆分 | 明确角色、场景使用独立 AI 配置并行解析，结果仍由父任务统一暂存和采纳。 |
| v2 | 架构与状态边界 | 采用非阻塞父任务协调器、按父任务串行化 JSON 写入，并固定父/子任务终态判定。 |
| v3 | 落地与验收细化 | 补充配置兼容、限流超时、可观测性、测试隔离和发布回滚检查项。 |
| v4 | 实现一致性收尾 | 对齐 `AiInvocationGuard` 限流、补齐子任务排队/取消状态、前端加载重试和提示词契约测试。 |

## 2. 背景与现状

当前 `ScriptAnalysisTaskService` 已支持在一次提交中为 `CHARACTER`、`SCENE_BACKGROUND` 和 `PROP` 分别传入 `provider_config_id` 与 `model`，并会将每类结果保存到 `ScriptAnalysisResult.typeResultsJson`。这已经具备不同提供商和模型的输入边界。

但 Worker 的 `execute(taskId)` 对 `selections` 使用 `forEachIndexed` 顺序执行：角色调用完成后才会调用场景。每类调用都使用同一个 `script_analysis` 提示词版本，仅通过 `asset_type` 变量区分。其问题是：

1. 总耗时接近所有模型调用耗时之和，慢速场景解析阻塞角色结果产出。
2. 角色与场景的提取目标、证据要求和输出字段不同，共用提示词难以独立优化和回滚。
3. 父任务的进度只反映串行阶段，无法准确展示每一类资产的运行状态。
4. 一个类型失败时，串行流程虽能继续，但没有明确的并发汇聚、取消和部分成功规则。

目标是将一个 `SCRIPT_ANALYSIS` 父任务拆为互不依赖的逻辑子任务。子任务共享同一份不可变剧本快照和父任务关联 ID，但各自独立配置、调用、审计、结果和失败状态。

## 3. 范围与非目标

### 3.1 范围

1. 首期并发 `CHARACTER` 与 `SCENE_BACKGROUND` 两个子任务。
2. 每个子任务独立选择提供商配置、模型和提示词版本。
3. 子任务结果独立暂存、查询、取消和采纳；父任务在所有子任务终态后汇聚状态。
4. 保持现有“AI 结果先暂存，用户采纳后才写入 `EpisodeAsset`”的边界。
5. `PROP` 复用相同数据结构和调度路径，但不要求本次界面默认启用。

### 3.2 非目标

1. 不把角色提取、场景提取拆成对外可独立提交的 API；一次提交仍创建一个父任务，避免剧本版本保护与采纳流程分叉。
2. 不引入通用 DAG 引擎、消息队列或跨进程任务调度器。
3. 不改变 `ScriptService.analyze` 的项目级同步兼容接口。
4. 不在 Worker 中自动创建或更新正式资产。
5. 不新增或修改数据库迁移脚本；本期在已有 `ScriptAnalysisResult` 的 JSON 字段中持久化子任务快照与结果。

## 4. 核心模型

### 4.1 父任务与逻辑子任务

一个用户提交对应一个持久化的 `ProductionTask(taskType = SCRIPT_ANALYSIS)`，它是任务中心、权限、取消和整体状态的唯一入口。每个被选择的资产类型对应一个逻辑子任务，持久化在同一 `ScriptAnalysisResult` 中：

| 对象 | 持久化位置 | 职责 |
| --- | --- | --- |
| 父任务 | `ProductionTask` | 项目/剧集权限、整体状态、整体进度、取消、关联 ID 和任务中心展示。 |
| 解析结果聚合 | `ScriptAnalysisResult` | 剧本版本和内容哈希快照、聚合结果、采纳记录。 |
| 逻辑子任务 | `selectionsJson` 和 `typeResultsJson` 的一个资产类型项 | 独立的运行状态、AI 配置、提示词快照、请求 ID、结果、错误和耗时。 |

首期不为每个子任务创建第二个 `ProductionTask`。现有 `ProductionTask` 没有稳定的 `parent_task_id` 持久化边界；直接创建两个同级任务会使任务中心取消、幂等提交和结果采纳失去唯一父级。逻辑子任务已具备逐类型持久化结果的位置，能在不进行迁移的前提下完成并发执行与审计。后续若任务中心补齐持久化父子关系，可将逻辑子任务投影为真实子任务，而不改变 API 或结果格式。

### 4.1.1 协调器职责

父任务执行器与子任务 AI 执行器必须分离：

| 组件 | 是否执行网络调用 | 是否等待子任务 | 唯一职责 |
| --- | --- | --- | --- |
| `ScriptAnalysisCoordinator` | 否 | 否 | 校验父任务、投递子任务、注册完成回调、触发最终汇聚。 |
| `ScriptAssetAnalysisService` | 是 | 单次调用内部等待上游响应 | 按冻结快照渲染提示词、调用 AI、校验和规范化单类结果。 |
| `ScriptAnalysisResultWriter` | 否 | 短事务内等待数据库 | 以父任务为键串行写入 `typeResultsJson` 和父进度。 |
| `ScriptAnalysisAggregator` | 否 | 等待所有子任务 Future 已完成 | 从数据库读取最终子结果，生成 `resultJson` 并推进父任务终态。 |

父任务协调器提交子任务后立即返回，不在父任务 Worker 线程上调用 `Future.get()` 或 `join()`。所有 Future 通过 `whenComplete` 汇入一个父任务级完成计数器；计数达到选择项数量后，再提交一次聚合工作。这样父任务 Worker 池的容量只用于领取和协调，不会因上游慢响应而被占满。应用重启恢复时，协调器从数据库重建未终态子任务并重新注册回调。

### 4.2 子任务选择快照

提交时，服务端对每个已选择类型生成并持久化以下不可变选择项。不得在执行时重新读取“当前默认提示词”或“页面当前模型”。

```json
{
  "asset_type": "CHARACTER",
  "provider_config_id": 12,
  "model": "character-model-v3",
  "prompt_key": "script_character_extraction",
  "prompt_version_id": 301,
  "template_hash": "sha256:...",
  "variables_hash": "sha256:...",
  "output_schema_version": "script_character_extraction.v1"
}
```

新增或扩展 `StoredSelection` 时，`asset_type` 是唯一键。对于兼容中的旧任务，如果没有 `prompt_key` 等字段，执行器按旧语义回退至已冻结的 `script_analysis` 提示词版本；新提交不得写入这种不完整快照。

### 4.3 提示词映射

首期默认映射如下。管理员可在提示词管理中发布新版本；发布不会影响已入队任务。

| 资产类型 | 默认 `prompt_key` | 输出根字段 | 关键侧重点 |
| --- | --- | --- | --- |
| `CHARACTER` | `script_character_extraction` | `characters` | 身份、外貌、性格、关系、动机、对白意图；区分剧本事实和无法确认的信息。 |
| `SCENE_BACKGROUND` | `script_scene_extraction` | `scenes` | 地点、时段、环境、天气、灯光、视觉风格、剧情功能和连续性。 |
| `PROP` | `script_prop_extraction` | `props` | 类别、外观、归属、功能和连续性；首期预留。 |

每个提示词有自己的变量 schema 和输出 schema。三类提示词至少共享 `script_content`、`episode_context_json`、`output_language` 和 `detail_level`；不再要求共享 `asset_type` 变量或完整的角色/场景/道具联合输出 schema。输出校验只接受当前类型对应的根字段，禁止模型借由另一个类型输出绕过字段白名单。

`PromptCatalogService.REGISTERED_KEYS`、内置提示词初始化和提示词管理界面需要在实现时登记上述 key。旧 `script_analysis` 仍用于已有任务和项目级同步解析，不应被原地改造成任一子任务提示词。

## 5. 接口与提交契约

继续使用现有提交入口：

```http
POST /api/projects/{project_id}/script/analysis-tasks
Idempotency-Key: optional-client-key

episode_id=3
version_id=88
content_hash=sha256:...
character_provider_config_id=12
character_model=character-model-v3
character_prompt_version_id=301
scene_background_provider_config_id=18
scene_background_model=scene-model-v2
scene_background_prompt_version_id=327
```

### 5.1 参数规则

1. 每类的 provider、model 和 prompt version 必须成组出现；只传部分参数返回 400。
2. `*_prompt_version_id` 为空时，服务端解析该类型默认 `prompt_key` 的当前已发布版本，并把最终版本 ID 和哈希冻结到子任务快照。
3. 用户显式传入版本 ID 时，服务端必须验证其属于该类型允许的 `prompt_key`、已发布、可执行且调用者有读取权限。不能传任意提示词版本。
4. 新接口接受角色和场景各自的提示词版本；`PROP` 使用同一命名规则，供后续开启。
5. `content_hash`、剧本版本、项目和剧集校验与现有流程保持一致。服务端读取当前正文并重新计算哈希，不能信任客户端摘要。
6. `Idempotency-Key` 的等价性包括资产类型、提供商配置、模型、提示词版本 ID 和模板哈希。相同键被用于不同选择项时返回 400。

### 5.2 兼容策略

1. 旧客户端只提交 `*_provider_config_id` 和 `*_model` 时，服务端为每类资产补齐默认提示词版本并写入完整快照；这不会暴露提示词版本选择给旧客户端。
2. 旧任务的选择项只有 `asset_type`、provider 和 model 时，恢复执行器使用任务创建时记录的 `prompt_version_id`、`template_hash`；如果旧数据没有版本快照，必须标记为 `LEGACY_SNAPSHOT_UNAVAILABLE` 并失败，不能读取当前提示词猜测历史输入。
3. 新客户端不得混用旧的数组参数（例如按下标对齐的 `asset_types`、`provider_config_ids`、`models`）和按类型命名参数；兼容期只允许服务端在入口一次性转换为统一选择项。
4. 提示词版本不可用、已归档且无法执行或哈希不匹配时，提交阶段返回 400；任务入队后发现异常只能把对应子任务标记为 `PROMPT_SNAPSHOT_INVALID`，不能改用其他版本静默重试。

请求成功仍返回父 `task_id`。查询详情时，`type_results` 增加子任务级提示词元数据和时序信息：

```json
{
  "type": "SCENE_BACKGROUND",
  "status": "RUNNING",
  "provider_config_id": 18,
  "model": "scene-model-v2",
  "prompt_key": "script_scene_extraction",
  "prompt_version_id": 327,
  "template_hash": "sha256:...",
  "progress": 50,
  "stage": "CALL_PROVIDER",
  "started_at": "2026-09-06T10:15:03"
}
```

所有时间使用 `LocalDateTime` 并按 ISO-8601 无时区格式传输。响应不返回 API Key、渲染后的完整提示词、完整剧本文本或原始上游响应。

## 6. 并发执行设计

### 6.1 调度模型

父任务的 Worker 只负责创建和汇聚子任务。它在短事务中将父任务从 `QUEUED` 置为 `RUNNING` 后，向专用的有界 `ThreadPoolExecutor` 提交各资产类型调用；网络调用不在数据库事务中执行。提交完成后 Worker 立即释放，子任务完成通过回调触发结果写入和最终汇聚。

```text
父 SCRIPT_ANALYSIS 任务
          |
          +-- CHARACTER 子任务 -- provider A / model A / character prompt
          |
          +-- SCENE_BACKGROUND 子任务 -- provider B / model B / scene prompt
          |
          +-- PROP 子任务（后续启用） -- provider C / model C / prop prompt
          |
          v
等待全部已提交子任务结束，汇聚并持久化父任务结果
```

实现使用显式注入的 `Executor` 配合 `CompletableFuture` 或等价的 Kotlin/JDK 并发 API，不使用 `parallelStream`、无界协程或公共 `ForkJoinPool`。每个子任务的异常必须转换为该类型自己的失败结果，不能让一个 Future 的异常提前中止其他类型。`whenComplete` 回调只负责投递短事务写入或聚合工作，不直接在 AI 线程中执行长时间数据库操作。

### 6.2 线程池与限流

建议初始配置如下，最终数值由压测和供应商额度决定：

| 配置 | 建议初始值 | 目的 |
| --- | --- | --- |
| 父任务 Worker 池 | 2 | 领取父任务、提交子任务和等待汇聚，不执行模型网络请求。 |
| 子任务 AI 池最大并发 | 4 | 在两个父任务同时运行且每个有角色/场景两项时，上限为 4 个 AI 调用。 |
| 子任务队列容量 | 32 | 防止高峰时无限占用内存。 |
| 单父任务最大并发 | 2 | 首期角色和场景同时开始；未来道具加入时仍避免一个父任务独占线程池。 |
| 单提供商并发上限 | 配置化，默认 2 | 保护同一提供商的速率限制和账户配额。 |

调度顺序为：先检查父任务是否取消或过期，再将子任务置为 `QUEUED` 并提交；AI 客户端内部的 `AiInvocationGuard` 按连接配置获取提供商级并发许可并执行断路器检查，最后调用 AI。队列满时，未开始的父任务在提交阶段失败为 `TASK_QUEUE_FULL`；运行中父任务不能只因单个子任务暂时无容量而失败，应保持 `RUNNING` 并等待受限槽位。

不同提供商可以真正并发；相同提供商也可以并发，但必须服从其连接档案中的专属额度。一个提供商的限流或故障只影响引用它的子任务。`AiInvocationGuard` 在响应式调用结束时释放许可；无法获取许可时由 AI 服务转换为 `AI_RATE_LIMITED`，不应永久占用槽位。子任务线程池拒绝提交时，协调器将该子任务置为 `FAILED/TASK_QUEUE_FULL`，继续等待其他已提交子任务，并按父任务终态真值表汇聚。

每个子任务的上游调用必须设置独立超时，当前实现的硬上限为 120 秒；超时后取消响应式订阅，由 `AiInvocationGuard` 释放提供商许可，并写入 `AI_TIMEOUT`。对不支持中断的 WebFlux 调用，取消只阻止结果落库，不能假设远端请求已经停止。

### 6.3 子任务生命周期

子任务状态使用如下机器值，存储在 `typeResultsJson`：

```text
PENDING -> QUEUED -> RUNNING -> SUCCEEDED
                         |
                         +-> FAILED
PENDING / QUEUED / RUNNING -> CANCELLED
```

每个状态变化均先短事务持久化，再更新父进度。建议字段如下：

| 字段 | 含义 |
| --- | --- |
| `status` | 子任务状态。 |
| `stage` | `QUEUED`、`CALL_PROVIDER`、`VALIDATE_OUTPUT`、`PERSIST_RESULT` 或终态说明。 |
| `progress` | 0、10、70、90、100 等固定阶段进度；网络调用阶段可标记不确定。 |
| `provider_config_id` / `model` | 冻结的模型选择。 |
| `prompt_key` / `prompt_version_id` / `template_hash` | 冻结的提示词选择。 |
| `request_id` | 本次 AI 调用唯一 ID。 |
| `result_hash` / `items` | 规范化后的结果及其内容摘要。 |
| `error_code` / `error_message` | 稳定错误码和脱敏说明。 |
| `started_at` / `completed_at` / `latency_ms` | 子任务耗时和诊断信息。 |

`ScriptAssetAnalysisService.analyze` 改为接收已解析的子任务提示词快照或明确的 `promptKey`、`promptVersionId` 与 `templateHash`，不得在执行时回退查询“当前”版本。审计记录继续以父 `task_id` 关联，并以 `request_id` 区分每次子调用；建议增加 `asset_type` 到审计输入摘要或事件元数据。

子任务状态迁移必须带当前状态条件，重复回调只能返回已持久化终态：

| 当前状态 | 允许迁移 | 触发者 |
| --- | --- | --- |
| `PENDING` | `QUEUED`、`CANCELLED` | 协调器、取消命令。 |
| `QUEUED` | `RUNNING`、`CANCELLED` | 子任务执行器、取消命令。 |
| `RUNNING` | `SUCCEEDED`、`FAILED`、`CANCELLED` | 子任务执行器或取消确认。 |
| 终态 | 不可迁移 | 任何重复回调均不得覆盖结果。 |

`CANCEL_REQUESTED` 继续作为父任务层的逻辑标记（当前枚举尚无该值）；子任务收到取消请求后只允许落为 `CANCELLED`。若上游响应和取消请求竞态发生，以先成功写入的终态为准：取消已写入后，迟到的成功结果必须丢弃；成功已写入后，取消只能返回已完成状态。

### 6.4 父任务进度与汇聚

父任务进度依据子任务数量及其阶段加权计算，而非以串行索引计算：

```text
parent_progress = floor(sum(child_progress) / selected_child_count)
```

父任务 `stage` 用以下规则生成：

1. 存在运行中子任务时为 `ANALYZING_ASSETS`。
2. 所有子任务均已提交但仍有排队项时为 `WAITING_FOR_ANALYSIS_SLOT`。
3. 全部子任务终态后为 `AGGREGATING_RESULTS`，聚合 `characters`、`scenes` 和 `props` 到 `resultJson`。
4. 聚合并持久化完成后，成功子任务至少一个则父任务为 `SUCCEEDED`，阶段为 `RESULT_STAGED` 或 `PARTIAL_RESULT_STAGED`；全部失败则为 `FAILED`。

聚合按资产类型的稳定顺序执行，只组合 `SUCCEEDED` 子任务的规范化结果。禁止在子任务完成时直接写入 `resultJson`，防止并发写入覆盖兄弟结果。父任务完成前由单一汇聚线程一次写入 `resultJson`、`resultStatus` 和最终进度。聚合任务必须具备幂等保护：同一父任务已经是 `SUCCEEDED`、`FAILED` 或 `CANCELLED` 时，重复聚合只读取并返回现有结果，不再次覆盖终态。

父任务终态判定固定为以下真值表：

| 子任务集合 | 父任务状态 | `result_status` | 是否允许采纳 |
| --- | --- | --- | --- |
| 全部 `SUCCEEDED` | `SUCCEEDED` | `STAGED` | 是，默认全部类型。 |
| 至少一个 `SUCCEEDED`，其余为 `FAILED` 或 `CANCELLED` | `SUCCEEDED` | `STAGED` | 是，仅成功类型。 |
| 全部 `FAILED` | `FAILED` | `PROCESSING` | 否。 |
| 用户取消导致全部未完成项为 `CANCELLED`，且无成功项 | `CANCELLED` | `PROCESSING` | 否；`DISCARDED` 只表示用户调用 discard 接口后的决定。 |
| 已有成功项后用户取消 | `CANCELLED` | `STAGED` | 否；取消语义优先，成功结果保留用于诊断但不可采纳。 |

`PARTIAL_RESULT_STAGED` 是父任务 `stage` 的展示值，不新增 `ProductionTaskStatus`；结果是否可采纳由 `result_status` 和成功类型集合共同决定。

### 6.5 失败、超时与取消

1. 一个子任务失败不会取消仍在运行的兄弟子任务；队列拒绝和信号量超时只影响对应子任务。
2. 至少一个子任务成功时，父任务进入 `SUCCEEDED`，并以 `PARTIAL_RESULT_STAGED` 表示部分结果；仅成功类型可采纳。
3. 所有子任务失败时，父任务进入 `FAILED`。详情必须保留每个子任务的脱敏错误，父任务错误使用首个稳定错误码或 `SCRIPT_ANALYSIS_FAILED`。
4. 用户取消父任务后，先以带状态条件的更新写入取消请求，再不调度 `PENDING` 或 `QUEUED` 子任务，并将它们标为 `CANCELLED`。已发出的调用执行尽力取消；供应商不支持中止时，响应返回后必须丢弃结果，不得转为 `SUCCEEDED` 或写入可采纳聚合结果。取消请求与子任务终态写入竞争时，以数据库中先成功的条件更新为准。
5. 子任务超时按该提供商/模型配置执行；超时归类为 `AI_TIMEOUT`，不阻塞兄弟子任务汇聚。
6. 父任务或剧本快照在任何子任务开始前已过期时，终止未开始子任务，并以 `SCRIPT_VERSION_SUPERSEDED` 结束父任务。执行中的调用返回后同样需要再次校验，以保证过期结果不能暂存为可采纳结果。

## 7. 数据一致性与并发安全

### 7.1 JSON 聚合并发写入

`ScriptAnalysisResult.typeResultsJson` 是单列 JSON 文本，不能由多个子任务以“读取、修改、保存”方式并发写入，否则会丢失兄弟项。实现必须采用以下一种策略：

1. 推荐：每个父任务建立一个短生命周期的串行写入链（例如 `ConcurrentHashMap<taskId, CompletableFuture<Unit>>`），所有子任务状态和结果更新都追加到该链；写入链只执行短事务，完成后在父任务终态或失败清理时移除。
2. 可选：每次状态更新使用 `findWithLockByTaskId` 取得悲观锁，合并当前 JSON 后立即提交短事务。不得在持锁事务内调用 AI。

无论采用哪种方式，最终汇聚前需从数据库重新读取完整 `typeResultsJson`，确认每个选择项均处于终态后再生成 `resultJson`。实体的 `@Version` 乐观锁冲突应重试有限次数；重试耗尽时父任务失败为 `ANALYSIS_RESULT_CONFLICT`，不能静默覆盖。串行写入链只保证单实例内顺序；若未来部署多实例，必须把结果更新移动到数据库条件更新、分布式锁或消息队列，不能依赖 JVM 内存 Map。

### 7.2 快照与幂等

1. 父任务、每个子任务选择项和每个 AI 调用均共享 `scriptVersionId`、`contentHash` 与 `correlationId`。
2. 内容、选择项、提供商、模型或提示词版本任一不同，都视为不同提交，不得复用 `Idempotency-Key` 的旧任务。
3. 应用重启恢复父任务时，读取持久化的选择快照：终态子任务不重跑，`RUNNING` 子任务先标记为可恢复排队项，再按同一快照重试；恢复不解析新提示词版本。
4. 结果采纳继续锁定 `ScriptAnalysisResult`，并只读取 `SUCCEEDED` 类型项。并发执行不改变采纳的内容哈希、版本校验或“只创建、不覆盖”资产合并规则。

### 7.3 单实例与多实例边界

首期执行器按单实例部署假设设计，JVM 内存中的父任务协调器只用于减少同一实例内的 JSON 竞争，不能作为跨实例协调事实。部署多个实例前必须完成以下之一：

1. 使用数据库条件更新和悲观锁，将每个子任务状态更新做成可重试的原子操作，并由定时扫描触发未完成父任务汇聚；或
2. 使用具备消费确认和幂等键的持久化队列/流，保证一个父任务的结果写入和聚合事件可被单一消费者串行处理。

在未完成多实例改造前，启动时必须通过配置或部署约束保证同一 `SCRIPT_ANALYSIS` 父任务只由一个实例执行；发现重复领取时，后领取者应放弃并记录 `DUPLICATE_DISPATCH`，不能同时调用 AI。

### 7.4 可观测性与审计

每个子任务至少输出以下结构化指标和日志字段：`task_id`、`correlation_id`、`asset_type`、`provider_config_id`、`model`、`prompt_version_id`、`request_id`、`status`、`latency_ms`、`queue_wait_ms`、`error_code`。日志中的剧本文本、API Key、渲染提示词和原始响应必须脱敏或不记录。

建议指标：

- `script_asset_child_active`：按资产类型和提供商统计运行中子任务数。
- `script_asset_child_latency_ms`：从 `RUNNING` 到终态的分位耗时。
- `script_asset_child_queue_wait_ms`：进入队列到开始调用的等待时间。
- `script_asset_child_failures_total`：按稳定错误码计数。
- `script_asset_parent_partial_total`：部分成功父任务数。
- `script_asset_cancel_late_result_total`：取消后收到但被丢弃的上游结果数。
- `script_asset_result_conflict_total`：JSON/乐观锁写入冲突数。

审计写入失败不能阻塞 AI 结果暂存，但必须记录 `AUDIT_WRITE_FAILED` 并触发告警；结果和任务状态仍以业务持久化为准。

## 8. 前端交互

资产解析弹窗在角色和场景每一行分别展示：启用开关、提供商、模型和提示词版本。默认值来自各类型的默认配置，用户修改一行不影响另一行。

任务进度页不使用“第 N 步”串行文案，而是并列展示子任务状态：

| 子任务 | 可显示状态 |
| --- | --- |
| 角色提取 | 排队中、解析中、已完成、失败、已取消。 |
| 场景提取 | 排队中、解析中、已完成、失败、已取消。 |

父任务完成且存在部分成功时，结果确认窗口展示可采纳类型与失败类型。采纳按钮只提交成功类型；失败类型可通过重新提交新的父任务重试，不能在原任务上替换提供商、模型或提示词配置。

前端不发送 API Key、提示词模板内容或任意 `prompt_key`。可选提示词版本应由服务端按资产类型和权限过滤后返回。

## 9. 实施计划

1. 扩展提示词目录：注册并发布 `script_character_extraction` 与 `script_scene_extraction` 的内置版本、变量 schema 和独立输出 schema；保留 `script_analysis` 兼容现有同步流程。
2. 扩展提交参数、`ModelSelection` 和 `StoredSelection`：为每类资产解析并冻结提示词 key、版本、模板哈希、变量哈希与输出 schema 版本。
3. 改造 `ScriptAssetAnalysisService`：从选择快照执行，按类型独立渲染、调用、校验和审计；删除对当前 `script_analysis` 默认版本的隐式依赖。
4. 改造 `ScriptAnalysisTaskService.execute`：引入受限子任务执行器、每类型状态机、并发等待与单点汇聚；保留现有父任务队列、任务中心和采纳服务。
5. 扩展任务查询响应：返回每个子任务的提示词快照、状态、进度、阶段、耗时与脱敏错误。
6. 调整前端弹窗和进度展示：支持角色、场景各自选择提示词版本，并列显示结果与部分失败。
7. 在压测后配置每提供商限流和线程池大小，观察队列深度、调用时延、限流错误率和取消成功率。

## 10. 测试与验收

### 10.1 服务测试

1. 角色与场景使用不同 provider、model、prompt version 时，两个 AI 调用并发开始，且各自收到正确的提示词和 schema。
2. 两个子任务均成功时，父任务为 `SUCCEEDED`、进度为 100，聚合结果包含角色和场景。
3. 角色成功、场景失败时，父任务为 `SUCCEEDED` 且阶段为 `PARTIAL_RESULT_STAGED`；只允许采纳角色。
4. 两个子任务均失败时，父任务为 `FAILED`，详情保留两个稳定错误码。
5. 一个子任务完成时，另一个子任务的 `typeResultsJson` 项不会丢失或被覆盖。
6. 取消排队父任务会取消未开始子任务；取消运行中父任务不会采纳或暴露稍后返回的结果。
7. 在同一草稿版本原地修改正文后，运行中和已完成任务都不能采纳。
8. 相同幂等键但不同提示词版本或模型时返回参数冲突；完全相同请求返回原父任务。
9. 应用重启恢复时，只重跑未终态子任务，且使用提交时冻结的提示词版本。
10. 子任务队列拒绝、提供商并发许可不足和单调用超时都会释放资源，并只影响对应类型；父任务仍按其余子任务结果正确汇聚。
11. 取消请求与迟到成功响应并发发生时，条件更新保证不会出现“已取消但可采纳”的结果。
12. 同一父任务重复触发聚合不会修改已落库终态或重复写入结果。
13. 特性开关关闭时，选择快照和提示词版本仍保持不变，只验证并发度降为 1。

### 10.2 接口与前端验收

1. 提交接口可分别传入角色和场景的提供商、模型和提示词版本，返回一个父 `task_id`。
2. 查询接口同时展示两个子任务的实时状态，不将尚未开始的场景错误显示为角色完成后的下一阶段。
3. 角色与场景分别使用不同 AI 提供商时，审计记录可按父任务、资产类型和请求 ID 追溯。
4. 部分成功结果确认页只允许用户选择成功类型采纳，采纳后资产库只出现所选类型。
5. 所有 API 时间字段均为 ISO-8601 `LocalDateTime`；响应不泄露密钥、完整提示词或原始上游响应。
6. 提供商、模型和提示词版本的下拉数据经过服务端权限过滤；客户端无法通过修改 `prompt_key` 或版本 ID 读取其他项目的私有提示词。

## 11. 发布与回滚

先以特性开关启用 `script_asset_parallel_extraction`。关闭并发时仍使用同一协调器、选择快照和结果格式，只将“单父任务最大并发”降为 1（必要时将子任务 AI 池容量降为 1）；不能回退到会重新读取 `script_analysis` 当前版本的旧执行路径。已经创建的并发父任务必须按其持久化选择快照继续完成或取消，不能切换到旧路径。提示词可通过发布新版本前滚或将旧版本重新发布为新版本回滚，不能修改已冻结版本。

上线后重点监控：父任务端到端时延、各子任务时延、并发队列深度、供应商限流率、部分成功率、结果 JSON 乐观锁冲突率和取消后的结果丢弃次数。若供应商额度不足，优先下调对应提供商的并发信号量，而不是关闭全部资产提取并发。
