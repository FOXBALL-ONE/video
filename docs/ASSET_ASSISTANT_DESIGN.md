# 资产清单助手设计

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | 首期已实现 |
| 适用版本 | 当前 Video 项目 |
| 关联模块 | AI 模板管理、资产清单页、剧集资产（角色/场景/道具详情）、模板运行审计、项目权限 |
| 数据库范围 | 不新增表；模板与技能沿用 `ai_templates` / `ai_template_versions` / `ai_skills`，调用留痕沿用 `ai_template_runs` |

本文定义剧集资产清单页（`03 / 07` 节点）右侧「资产清单助手」的服务端能力。此前该面板只有一段静态文案与前端本地的假回复，节点里的 AI 能力（资产提取、角色扩写、参考图生成）都没有和它连通。

## 2. 一页决策摘要

| 决策 | 结论 |
| --- | --- |
| 助手职责 | 读取当前剧集资产 → 回答问题 → 产出**可审阅的字段修改建议** |
| 写入时机 | 建议不落库；用户在面板上勾选并点击「应用选中」后才写入 |
| 调用方式 | 同步一次模型往返（工具调用循环在服务端完成），超时 150 秒 |
| 可改字段 | 由 `AssetFieldCatalog` 单点声明，模型、内置工具、写入逻辑共用同一份目录 |
| 当前值来源 | 服务端回填，模型只提供 `value`，前端拿到的 `current_value` 永远来自数据库 |
| 并发保护 | 应用时对比 `expected_value`，字段已被他人改动则拒绝该条并回传最新值 |
| 权限 | 提问要求项目读权限，应用修改要求项目写权限 |
| 审计 | 复用 `ai_template_runs`，`task_id` 留空（助手不是异步任务） |
| 新增表 | 无 |

## 3. 目标与非目标

### 3.1 目标

1. 助手能看到当前剧集的真实资产：名称、类型、状态、描述、参考图链接、连续性备注，以及角色/场景/道具的扩展字段与图片就绪情况。
2. 用户可以用自然语言提问（「本集还有哪些资产没准备好」「林夏的服装描述能补全吗」），助手基于真实数据回答。
3. 助手能把「该怎么改」变成一份带理由的字段修改清单，逐条可勾选、可忽略。
4. 采纳修改走服务端校验：字段合法性、资产归属、取值长度与枚举、当前值是否已变化。
5. 一次模型调用不可能悄悄改坏创作数据：模型返回的 `current_value` 一律不可信。

### 3.2 非目标

1. 不新增或删除资产（新增仍走剧本解析任务与「添加资产」）。
2. 不修改需要文件库参与的字段：三视图、表情图、参考图文件、配音绑定。
3. 不做跨剧集的资产治理，也不做项目级资产清单助手。
4. 不做服务端会话存储：多轮上下文由前端在每次请求里带上最近若干轮。
5. 不引入向量检索或长文档切片，资产数量超出预算时按重要性裁剪并如实告知模型。

## 4. 总体流程

```text
前端面板 ── POST /assets/assistant/messages ──┐
   （消息 + 最近若干轮对话 + 模型）           │
                                            ▼
                            AssetAssistantService.propose
                              1. 权限与剧集校验
                              2. 读取资产 + 角色/场景/道具详情 → 资产清单 JSON（按预算降级）
                              3. 模板渲染：资产清单、剧集上下文、字段目录、对话、用户请求
                              4. TemplateAgentRunner：注入 asset_supervisor 技能与 asset_patch_validate 工具
                              5. 结构化输出校验 → 补丁映射（回填服务端当前值、丢弃非法项）
                              6. 写入模板运行审计
                                            │
   前端渲染回答 + 建议清单 ◀─────────────────┘
                                            │
   用户勾选 ── POST /assets/assistant/patches/apply ──┐
                                            ▼
                            AssetAssistantService.applyPatches
                              1. 写权限校验
                              2. 逐条校验：资产归属、字段是否属于该类型、长度与枚举、
                                 expected_value 是否仍等于当前值
                              3. 按资产维度合并保存（资产本体 + 对应详情），返回逐条结果
```

## 5. 模板、技能与内置工具

| 组成 | 名称 | 作用 |
| --- | --- | --- |
| 模板 | `asset_list_assistant`（分类复用 `SCRIPT_GENERAL`，见第 10 节） | 变量契约、输出契约与生成策略 |
| 技能 | `asset_supervisor`（资产统筹） | 检查维度与产出纪律：完整性、连续性、可生成性、一致性；改动最小化 |
| 工具 | `asset_patch_validate` | 校验建议的资产、字段、取值与长度，返回可用补丁与被丢弃条目 |

模板变量：`episode_context_json`、`asset_list_json`、`field_catalog_json`、`conversation_json`、`user_request`、`output_language`。
输出契约：`reply`、`patches[{asset_id, field, value, reason}]`、`warnings[]`、`suggestions[]`。

种子内容集中在 `AssetAssistantTemplate`，由 `TemplateCatalogInitializer` 在启动时发布；契约测试用同一个常量跑真实的 `TemplateSchemaValidator`，模板 JSON 写错会在测试阶段暴露，而不是等到首次调用助手。

`AssetPatchTool` 是纯函数：不读数据库，只按字段目录校验结构。资产是否存在、字段当前值是否已变化由应用阶段的服务端校验决定——模型自校验的结果不被信任。

## 6. 可修改的字段目录

`AssetFieldCatalog` 是唯一来源，同一份目录注入模板（模型据此选择 `field`）、装配给工具（结构校验）并驱动写入。

| 适用范围 | 字段 |
| --- | --- |
| 所有资产 | `name`、`description`、`reference_url`、`notes`、`status` |
| `CHARACTER` | `identity_title`、`role_tag`、`gender`、`age_stage`、`age_note`、`face_description`、`costume_description`、`hairstyle`、`costume_ref`、`accessory_ref` |
| `SCENE_BACKGROUND` | `identity_title`、`location`、`time`、`atmosphere` |
| `PROP` | `identity_title`、`category`、`plot_function` |

`status` 为枚举字段，取值来自 `AssetStatus`。声音、音乐与音效资产只有共有字段可改。场景与道具的 `description` 落在资产本体上（与 `AssetService.saveSceneProfile` / `savePropProfile` 的语义一致）。

## 7. 接口设计

### 7.1 提交一轮对话

`POST /api/projects/{project_id}/episodes/{episode_id}/assets/assistant/messages`

```json
{
  "provider_config_id": 3,
  "model": "gpt-4o-mini",
  "message": "本集还有哪些资产没准备好",
  "history": [{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
}
```

响应 `data`：

```json
{
  "reply": "本集 12 个资产里还有 4 个处于草稿……",
  "patches": [
    {
      "asset_id": 31, "asset_name": "林夏", "asset_type": "CHARACTER",
      "field": "face_description", "field_label": "人脸描述",
      "value": "鹅蛋脸，细眉，左眉尾有一道浅疤",
      "current_value": "", "reason": "参考图已就绪但缺少人脸描述，画师无法保持角色一致", "changed": true
    }
  ],
  "warnings": ["「旧相机」没有参考图"],
  "suggestions": ["检查本集资产缺口", "补全角色一致性描述"],
  "context": {
    "episode_id": 8, "episode_title": "雨夜来信", "episode_no": 2,
    "asset_count": 12, "ready_count": 8, "draft_count": 4, "completeness": 66,
    "type_counts": {"CHARACTER": 3, "SCENE_BACKGROUND": 4, "PROP": 5},
    "asset_list_truncated": false, "omitted_asset_count": 0
  },
  "provider": "openai", "model": "gpt-4o-mini", "request_id": "…",
  "template_key": "asset_list_assistant", "template_version_id": 12,
  "input_tokens": 8123, "output_tokens": 620
}
```

`changed` 表示建议值与服务端当前取值不同；`current_value` 由服务端回填，值为空串表示字段尚无内容。模型提出的未知字段、非法枚举、空值、重复字段会被丢弃并写入 `warnings`，不会出现在 `patches` 里。

失败响应：`400` 参数或业务校验失败（如「当前剧集还没有资产」「单条消息不能超过 8000 字」）；`502` 模型不可用（`data.error_code` 为 `AI_PROVIDER_ERROR` / `INVALID_OUTPUT_SCHEMA` 等）；`429` 触发限流；`504` 超时。

### 7.2 应用选中的修改

`POST /api/projects/{project_id}/episodes/{episode_id}/assets/assistant/patches/apply`

```json
{
  "items": [
    {"asset_id": 31, "field": "face_description", "value": "鹅蛋脸，细眉……", "expected_value": ""}
  ]
}
```

响应 `data`：

```json
{
  "applied": 1, "unchanged": 0, "rejected": 0,
  "items": [
    {"asset_id": 31, "field": "face_description", "status": "APPLIED", "code": null, "message": null, "current_value": ""}
  ]
}
```

逐条 `status`：`APPLIED`（已写入）、`UNCHANGED`（与当前取值一致，未写入）、`REJECTED`（未通过校验）。`REJECTED` 的 `code` 取值：

| code | 含义 |
| --- | --- |
| `ASSET_NOT_FOUND` | 资产不存在或不属于当前剧集 |
| `FIELD_NOT_ALLOWED` | 字段不属于该资产类型 |
| `INVALID_VALUE` | 取值不符合长度或枚举约束 |
| `ASSET_FIELD_CHANGED` | 字段已被其他操作修改，`current_value` 返回最新值 |
| `DUPLICATE_PATCH` | 同一资产的同一字段在一次提交中出现多次 |

`ASSET_FIELD_CHANGED` 时前端把最新值写回建议卡片并保持可再次应用，由用户重新确认，而不是覆盖他人的改动。

## 8. 服务端实现要点

1. **输入预算**：资产清单按三档细节构建（长文本 4000 → 1200 → 400，备注 800 → 300 → 0），仍然超出 15 万字符时按「角色 → 场景 → 道具 → 其它」保留前 300 个，并在 `context` 中回报 `asset_list_truncated` 与 `omitted_asset_count`，助手据此在回答中说明自己没看到全部资产。模板的 `max_input_tokens` 与变量 `maxLength` 留有余量，渲染超限会以明确错误返回，而不是静默截断提示词。
2. **当前值不来自模型**：`current_value` 与 `field_label` 由服务端从实体读取，模型只提供 `value`。
3. **同步调用**：单轮对话在一次模型往返内完成，统一 150 秒超时。超时或失败不会留下半成品数据——建议本来就只在用户确认后才写入。
4. **审计**：成功与失败都写入 `ai_template_runs`（`task_id` 为空），记录模板版本、模型、耗时、token 用量与工具调用次数；不保存提示词正文与资产正文。
5. **权限**：`canReadProject` 决定能否提问，`canWriteProject` 决定能否应用；无权限时 `propose` 返回 `404`、`applyPatches` 返回 `403`。

## 9. 前端接入

1. `AssistantPanel` 从纯展示组件扩展为可承载对话：`messages`、`loading`、`disabled`、`contextTitle`、`contextTags`、`status`，并新增 `composer-extra`（模型选择）与 `message-extra`（建议清单）两个插槽；仍保留 `message` 属性，未接入对话的节点（视频节点）显示不变。
2. 资产页在面板内完成一轮对话的完整闭环：提问 → 展示回答与建议 → 勾选 → 应用 → 刷新资产清单。切换剧集时清空对话与建议。
3. 建议卡片展示「新值 / 原值 / 理由」，与当前取值一致的建议默认不可勾选并标注原因；应用结果以颜色区分已应用、被拒绝与未变化。
4. 模型选择沿用页面已有的对话模型下拉，默认取第一个可用模型。

## 10. 边界与后续

1. **同步调用的取舍**：助手不是任务中心的异步任务，因此没有排队与进度查询，也不会出现在任务列表里。若后续要接入更长的多轮分析或批量重写，应改为任务化（`ProductionTaskType` 新增类型 + 结果 staging，参考角色扩写），前端轮询后再展示建议。
2. **不新增资产**：助手只能改现有资产。若要在回答里指出「剧本里出现但还没有资产的角色」，需要把剧本解析结果一并注入模板变量，并把输出契约扩展出 `new_assets`；写入侧可以复用 `AssetService.adoptAnalysisAssets` 的去重逻辑。
3. **缺失字段的证据来源**：当前只把资产清单与剧集上下文交给模型，助手无法引用剧本原文。补充剧本片段作为变量后，建议的依据会更强，但需要同步扩大输入预算。
4. **多轮记忆**：历史只保留最近 6 轮且总计不超过 1.8 万字符，更早的对话会被丢弃；服务端不落库会话，刷新页面即重新开始。
5. **图片类字段**：三视图、四视图、多角度、光照、表情、配音绑定不在字段目录内，助手只能在 `warnings` 里提示，实际生成仍走既有的图像生成与合并三视图任务。
6. **模板分类**：现有库的 `ai_templates.category` 带 `ai_templates_category_check` 约束，只允许最初的 7 个分类取值，而 `ddl-auto=update` 不会更新已存在的 CHECK 约束——新增枚举值会让应用在启动时因违反约束而失败。因此助手模板复用 `SCRIPT_GENERAL` 分类；若后续需要独立的「资产统筹」分类，必须先同步数据库约束（`ALTER TABLE ai_templates DROP CONSTRAINT ai_templates_category_check` 后重建，或补迁移脚本），再引入枚举值。`AssetAssistantTemplateContractTest` 已把数据库允许的取值固化为断言，避免再次踩坑。
