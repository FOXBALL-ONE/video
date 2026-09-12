# AI 模板管理模块设计

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | 首期已实现 |
| 适用版本 | 当前 Video 项目 |
| 关联模块 | Spring AI 客户端、任务中心、剧本导入与 AI 资产解析、角色扩写、分镜图生成、项目权限 |
| 数据库范围 | 本文只定义逻辑模型；表结构由 Hibernate `ddl-auto` 维护，不新增迁移脚本 |

本文定义服务端的 AI 模板管理模块。该模块取代原“提示词管理模块”：一个模板不再只是一段提示词正文，而是把**消息模板、变量契约、输出契约、生成策略、技能（SKILL）、MCP 服务端与内置工具**打包成一个可版本化、可审计、可复现的执行单元。

设计目标不是提供更花哨的提示词编辑器，而是让“一次 AI 产出到底用了什么指令、什么方法论、什么工具”成为可追溯的业务资产。

## 2. 一页决策摘要

| 决策 | 结论 |
| --- | --- |
| 选择单位 | 业务代码使用稳定的 `template_key`，运行时解析到一个已发布 `AiTemplateVersion` |
| 版本不可变 | `DRAFT` 可覆盖编辑；进入评审或发布后只能通过复制新版本修改 |
| 任务一致性 | 创建任务时固定版本 ID 与 `content_hash`，执行前重新校验哈希，内容变化即拒绝执行 |
| 技能注入方式 | 技能正文作为独立 `<skill>` 区块注入 system 通道，不参与变量替换 |
| MCP 工具装配 | 模板版本按 `mcp_servers_json` 引用服务端，工具名统一加服务端前缀 |
| 内置工具 | 由项目代码实现的纯函数工具，与 MCP 工具一并装配到同一次调用 |
| 生图 | 由生成策略 `output_mode=image` 驱动：先渲染（或先由模型改写）提示词，再交给图像模型 |
| 模型客户端 | 对话与图像生成走 Spring AI；模型目录、图像编辑、视频生成保留在自研客户端 |

## 3. 目标与非目标

### 3.1 目标

1. 用 `template_key` 稳定选择模板，例如 `script_analysis`、`character_enrichment`、`shot_storyboard_grid`。
2. 支持全局、项目、个人三层作用域，并按明确优先级解析有效版本。
3. 支持不可变版本、草稿/评审/发布/归档状态、发布人与回滚。
4. 为每个版本声明变量 schema、输出 JSON Schema、生成策略、技能绑定、MCP 绑定与内置工具绑定。
5. 模板执行统一经 Spring AI 的模型与工具调用循环完成，业务服务不自行拼装上游 HTTP 请求。
6. 运行审计记录版本、哈希、技能、MCP、工具调用次数、token 用量与稳定错误码。

### 3.2 非目标

1. 不创建或修改数据库迁移脚本。
2. 不维护按供应商划分的 JSON Schema 能力矩阵，兼容性由调用层处理。
3. 不引入通用 DAG 引擎或跨进程任务调度器。
4. 不把模板正文加密存储；沿用现有数据库权限与服务端作用域校验。
5. 本期不实现向量检索、批量评估与模板市场。

## 4. 数据模型

### 4.1 `ai_templates`（模板定义）

`template_key`、`display_name`、`purpose`、`category`、`is_category_default`、`scope_type`、`project_id`、`owner_user_id`、`default_locale`、`enabled`、审计字段与 `@Version` 乐观锁。

`category` 取值：`SCRIPT_GENERAL`、`SCRIPT_CHARACTER`、`SCRIPT_SCENE`、`SCRIPT_PROP`、`CHARACTER_ENRICHMENT`、`CHARACTER_EXPRESSION_IMAGE`、`STORYBOARD_IMAGE`。分类由 `template_key` 决定，数据库列只作为旧数据兜底。

### 4.2 `ai_template_versions`（不可变版本）

| 字段 | 说明 |
| --- | --- |
| `messages_json` | 消息模板数组，元素为 `{role, content}`，必须同时包含 system 与 user |
| `variables_schema_json` | 变量契约；消息中的 `{{var}}` 必须在此声明 |
| `output_schema_json` | 输出 JSON Schema（受支持子集） |
| `generation_policy_json` | 生成策略，见第 5 节 |
| `skills_json` | 技能绑定，形如 `[{"key":"director"}]` |
| `mcp_servers_json` | MCP 绑定，形如 `[{"key":"filesystem","tools":["read_file"]}]` |
| `tools_json` | 内置工具名数组，形如 `["storyboard_list_validate"]` |
| `compatibility_json` | 保留字段，仅参与内容哈希，当前不参与调用 |
| `content_hash` | 上述字段与 locale 的规范化 SHA-256，任务快照据此固定执行内容 |

`content_hash` 覆盖技能、MCP 与工具绑定：只新增一条技能绑定也会改变哈希，从而使在途任务的哈希校验按预期失败并要求重新提交。

### 4.3 `ai_skills`（技能）

| 字段 | 说明 |
| --- | --- |
| `skill_key` | 稳定 key，格式 `[a-z][a-z0-9_]{1,99}` |
| `instructions` | 技能正文（Markdown），禁止出现 `{{`，避免污染指令区 |
| `tools_json` / `mcp_servers_json` | 技能声明的默认工具与服务端，执行时与模板声明合并去重 |
| `source` | `BUILTIN`（随代码发布）/ `FILE`（`storage/skills` 导入）/ `DATABASE`（管理接口创建） |
| `content_hash` | 正文与元数据的哈希；文件技能按哈希判断是否需要更新 |

文件技能格式：`{file.storage-path}/skills/<key>/SKILL.md`

```markdown
---
key: director
display_name: 导演
description: 从导演视角完成剧本解析与分镜拆分
tools: storyboard_list_validate, shot_continuity_check
mcp_servers: filesystem
enabled: true
---
技能正文……
```

规则：`BUILTIN` 与 `DATABASE` 技能不会被文件导入覆盖；文件技能只在内容哈希变化时更新。

### 4.4 `ai_mcp_servers`（MCP 服务端）

`server_key`、`display_name`、`description`、`transport`（`STDIO` / `SSE` / `STREAMABLE_HTTP`）、`command`、`args_json`、`env_json`、`url`、`headers_json`、`tool_allowlist_json`、`request_timeout_seconds`、`enabled`。

约束：`STDIO` 必须提供启动命令，`SSE` 与 `STREAMABLE_HTTP` 必须提供 HTTP(S) 地址；`env_json` 与 `headers_json` 必须是标量值组成的 JSON 对象。

安全边界：连接信息只在服务端保存与使用；管理接口返回 `env` / `headers` 时统一替换为掩码，不回传真实值；读写接口均要求系统管理员权限。

### 4.5 `ai_template_runs`（运行审计）

`template_key`、`template_version_id`、`task_id`、`invocation_id`、`provider_config_id`、`model_name`、`status`、`attempt`、`latency_ms`、`input_tokens`、`output_tokens`、`template_hash`、`variables_hash`、`rendered_input_hash`、`skills_json`、`mcp_servers_json`、`tool_call_count`、`validation_error_code`。

审计不保存渲染后的提示词正文，也不保存业务输入，只保留可复现所需的哈希与统计。失败路径同样写入审计：技能与 MCP 绑定从模板版本回读，渲染前失败（例如参考图已变化）也会按任务快照中的版本写入审计行。

## 5. 生成策略

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `temperature` | 0.0 – 2.0 | 透传给对话模型 |
| `max_completion_tokens` | 1 – 1 000 000 | 单次输出上限 |
| `max_input_tokens` | 1 – 1 000 000 | 渲染阶段的输入预算（按字符数 / 4 估算），超限直接拒绝渲染 |
| 必填变量 | — | 顶层 `required` 中的变量若既未提供又没有 `default`，渲染前直接报错，不会用空串静默通过 |
| `response_format` | `text` / `json_object` / `json_schema` | 结构化输出；`json_schema` 使用本版本 `output_schema_json` |
| `output_mode` | `text` / `json` / `image` | 产出形态；未声明时按 `response_format` 推断 |
| `image.size` | 字符串，≤ 20 字符 | 例如 `2048x2048`、`2K` |
| `image.count` | 1 – 10 | 生成张数 |
| `image.quality` / `image.style` | 字符串 | 透传给图像接口 |
| `image.prompt_source` | `model_output` / `rendered_message` | 提示词来源：模型产出，或渲染后的消息正文 |

`output_mode=image` 且 `prompt_source=model_output` 时，模板先执行一次对话（技能与工具生效），再把模型产出的文本作为图像提示词；`rendered_message` 则跳过对话，直接把渲染结果交给图像模型，适用于只携带图像模型、没有对话模型的场景（例如角色合并三视图）。

消息中的 `{{` 一律按占位符处理：未被 `{{name}}` 语法匹配的 `{{` 在保存版本时即被拒绝，因此渲染阶段不会出现“无法解析的变量”。变量值（例如剧本原文）里出现的 `{{` 属于业务正文，不参与占位符解析。

## 6. 内置工具

内置工具由项目代码实现，无需外部进程或网络服务，以 JSON 字符串为入参出参。管理端可通过 `GET /api/ai/template-tools` 读取可用工具名与说明：

| 工具 | 作用 |
| --- | --- |
| `storyboard_list_validate` | 校验并规范化分镜列表：补全序号、时长缺省按 3 秒、超范围裁剪、收集警告 |
| `shot_continuity_check` | 检查分镜连续性：缺画面描述、相邻镜头重复、有台词未标角色、超长镜头 |

模板版本通过 `tools_json` 引用工具名，技能可通过自身 `tools_json` 声明同一批工具。工具名必须已注册，否则模板无法发布。MCP 工具受两层限制：服务端配置里的 `tool_allowlist_json`（管理员维护，未列入的工具不会暴露给模型）与模板/技能绑定里的 `tools` 白名单；两层都为空表示该服务端全部工具。技能声明的 MCP 服务端会与模板声明合并后再装配。

## 7. 执行流程

```text
业务服务（剧本解析 / 角色扩写 / 分镜图）
        │  1. 提交任务：固定 template_version_id + content_hash
        ▼
TemplateCatalogService   2. 解析版本、校验作用域/分类/语言/哈希
        ▼
TemplateRenderer         3. 变量校验与渲染（转义 & < >，预算校验）
        ▼
TemplateAgentRunner      4. 注入技能 system 区块
        │                 5. 装配内置工具 + MCP 工具回调
        │                 6. Spring AI 工具调用循环（最多 6 轮）
        │                 7. 结构化输出校验（output_schema_json）
        ▼
TemplateRunAuditService  8. 写入运行审计
```

以剧本解析为例：`script_analysis` 模板引用 `director` 技能；`director` 技能声明 `storyboard_list_validate` 与 `shot_continuity_check` 两个内置工具。执行时技能正文作为 system 区块注入，工具随请求装配给模型，模型可在产出最终 JSON 前调用工具做结构校验。

分镜九宫格（`shot_storyboard_grid`）走的是图像路径：任务只需携带图像模型时直接使用渲染结果成图；当提交方额外提供 `prompt_provider_config_id` 与 `prompt_model` 时，先由 `storyboard_artist` 技能组织画面并产出提示词，再交给图像模型。

工具循环使用 Spring AI 的 `ToolCallingManager` 语义：工具结果回填到对话历史后模型继续推理，轮数上限 6 轮，避免模型在工具结果不理想时无限循环。

## 8. 接口清单

### 8.1 模板（`/api/ai/templates`）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/ai/templates` | 目录列表，支持 `key`、`scope_type`、`project_id`、`status`、`category` 过滤 |
| GET | `/api/ai/templates/options` | 剧本资产解析的模板选择项（`project_id`、`asset_type`、`locale`） |
| GET | `/api/ai/templates/{template_id}/versions` | 版本列表 |
| GET | `/api/ai/templates/{template_id}/versions/{version_id}` | 版本详情（含 `skills`、`mcp_servers`、`tools`） |
| POST | `/api/ai/templates` | 新建模板与首个草稿版本 |
| PUT | `/api/ai/templates/{template_id}` | 更新模板元数据（名称、用途、默认语言、启用状态） |
| DELETE | `/api/ai/templates/{template_id}` | 删除模板（存在已发布版本时拒绝） |
| PUT | `/api/ai/templates/{template_id}/versions/{version_id}` | 编辑草稿版本 |
| POST | `/api/ai/templates/{template_id}/versions` | 复制版本生成新草稿 |
| DELETE | `/api/ai/templates/{template_id}/versions/{version_id}` | 删除草稿版本 |
| POST | `/api/ai/templates/{template_id}/versions/{version_id}/{submit-review\|reject-review\|publish\|archive\|rollback}` | 版本状态流转 |
| POST | `/api/ai/templates/{template_id}/versions/{version_id}/preview` | 脱敏预览：只返回哈希、消息数与 token 估算 |
| GET | `/api/ai/template-tools` | 内置工具清单（名称与描述） |

模板正文的写权限沿用作用域模型：全局模板要求系统管理员，项目模板要求项目所有者，个人模板只允许本人并在个人项目中生效。

### 8.2 技能（`/api/ai/skills`）

| 方法 | 路径 | 权限 |
| --- | --- | --- |
| GET | `/api/ai/skills` | 已登录用户（不含正文） |
| GET | `/api/ai/skills/options` | 已登录用户（模板编辑器选择项） |
| GET | `/api/ai/skills/{skill_id}` | 系统管理员（含正文） |
| POST / PUT / DELETE | `/api/ai/skills[...]` | 系统管理员 |
| POST | `/api/ai/skills/import` | 系统管理员（扫描 `storage/skills`） |

### 8.3 MCP 服务端（`/api/ai/mcp-servers`）

全部接口要求系统管理员：列表与详情返回掩码后的 `env` / `headers`；`GET /api/ai/mcp-servers/{server_id}/tools` 为连通性测试，返回该服务端当前暴露的工具名。

## 9. 客户端边界

| 能力 | 实现 |
| --- | --- |
| 对话补全（含流式） | Spring AI `OpenAiChatModel` / `ChatClient` |
| 图像生成 | Spring AI `OpenAiImageModel` |
| 工具调用循环 | Spring AI `ToolCallingManager` + `SyncMcpToolCallbackProvider` |
| 模型目录 | 自研 `OpenAiCompatibleClient` |
| 图像编辑 | 自研 `OpenAiCompatibleClient`（multipart `image[]` 与可选 `mask`，Spring AI 未覆盖） |
| 视频生成与下载 | 自研 `OpenAiCompatibleClient`（Spring AI 无对应能力） |

连接档案（代理、DNS、超时、并发、自定义请求头）仍来自数据库中的提供商配置：`SpringAiModelFactory` 按连接构建并缓存 Spring AI 模型，提供商配置变更时由 `AiProviderResolver` 统一失效缓存；重复重试统一由 `AiInvocationGuard` 与业务重试策略控制，Spring AI 侧的重试次数固定为 0，避免同一请求被叠加放大。

Spring AI 的调用超时映射到 OpenAI SDK 的请求超时（OkHttp `callTimeout`，覆盖整个调用含响应读取），而自研客户端使用 Reactor Netty 的响应超时（分块间隔）。同一个 `response-timeout` 配置在两条路径上的语义略有差异，长响应场景请按整段调用时间配置。

`application.yaml` 中关闭了 Spring AI 的按属性自动装配（`spring.ai.model.*=none`，包含 chat / image / embedding / moderation / audio.speech / audio.transcription，以及 `spring.ai.mcp.client.enabled=false`），因为本项目所有模型连接都来自数据库配置。

## 10. 已知边界与后续

1. 任务快照中沿用 `prompt_key`、`prompt_version_id`、`*_prompt_version_id` 等字段名：这些字段已持久化在历史任务与剧本版本中，改名需要数据迁移，本期保留字段名，只把模块与接口更名为模板。
2. 模板执行期为非流式：工具调用循环按单次请求完成，流式输出仍可用于 `AiChatController` 的原始对话接口，但不参与模板执行。
3. 未验证非 OpenAI 兼容协议的提供商：模板选项构造使用了 `OpenAiChatOptions`，接入其它协议时需要新增适配层。
4. 内置工具为纯函数；需要读写数据库或调用外部系统的工具应通过 MCP 服务端提供。
5. `compatibility_json` 目前只参与内容哈希，尚未用于供应商能力协商。
6. 内置模板内容随代码升级：启动时若内置内容哈希与已发布版本不一致，会生成新的已发布版本并归档旧版本；既有任务仍按快照中的版本 ID 执行（已归档版本可执行）。
