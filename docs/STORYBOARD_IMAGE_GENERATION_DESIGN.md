# 分镜图生成（九宫格）下一步设计

## 1. 文档信息

| 项目 | 内容 |
| --- | --- |
| 状态 | 设计稿，待评审 |
| 适用版本 | 当前 Video 项目 |
| 关联模块 | 分镜、AI 图片生成、任务中心、提示词目录、文件服务、视频制作 |
| 前端页面 | `frontend/app/pages/graph/storyboard.vue`（已落地） |
| 参考样例 | `frontend/cankao/分镜图生成.html`（薪火平台「九宫格连续分镜」交互） |

本文定义分镜图生成（分镜九宫格）功能下一步的后端接口、数据模型与实施阶段。前端页面已完成首批实现，生成、裁切预览、格位映射与填入均可交互，但生成结果与映射仅暂存于页面内存；本文所列接口落地后，前端切换到持久化链路。

## 2. 背景与现状

### 2.1 前端已完成

- 按镜号顺序每 9 镜自动切为一批九宫格，批头部展示镜头区间、场景标注（分镜组 `scene_label`）、总时长与生成状态。
- 「生成九宫格」当前直连 `POST /api/ai/images/generations`，返回 `file_id` 与签名 URL 后在页面内展示，并叠加 3×3 格位选择。
- 裁切预览使用 CSS 三倍背景裁切（`background-size: 300% 300%`），无需服务端切图即可预览单格。
- 点击格位 → 点击镜头行可重新映射 cell→shot；「填入分镜」目前只更新页面内状态；「导出映射」输出 JSON（batch_no、grid_file_id、cell/shot_id 列表）。
- 生成设置弹窗：图片模型（`/api/ai/model-providers` 过滤 `api_type=IMAGE`）、画板尺寸、风格补充；提示词由前端拼装（批内镜头 prompt/画面描述/景别/运镜 + 九格一致性约束）。

### 2.2 后端现有能力

- `ShotController`：分镜与分镜组的 CRUD、连续性检查（`/api/projects/{p}/shots...`）。
- `AiMediaController`：`/api/ai/images/generations`、`/api/ai/images/edits` 等同步生成接口。
- `ProductionTask` + `TaskCenterService`：任务生命周期、幂等键、取消、审计（参见 `docs/TASK_CENTER_DESIGN.md`）。
- `CharacterThreeViewMergeTaskService`：图片编辑类异步任务的成熟范式（队列限流、提示词目录版本校验、断线恢复、输出回填）。
- `PromptCatalogService` / `PromptRenderer` / `PromptRunAuditService`：提示词目录、渲染与调用审计。
- `FileService`：文件落盘、签名下载 URL。

### 2.3 现存缺口

1. 九宫格生成无项目内归属：直连 `/api/ai/images/generations` 产生的文件不与剧集关联，无审计与重试。
2. 批次与格位映射无持久化：刷新页面后生成结果与 cell→shot 映射全部丢失。
3. 无服务端切图：单镜画面无法落库，下游「画面视频」阶段拿不到每镜首帧。
4. `shots` 表没有分镜图/首帧字段。

## 3. 目标与非目标

### 目标

- 九宫格生成任务化：入库 `ProductionTask`，支持幂等、队列限流、取消、失败恢复与提示词审计。
- 批次（含格位映射）持久化，页面刷新可恢复。
- 服务端按 1/3 切分九宫格为单镜图并绑定到镜头，供画面视频阶段使用。
- 前端 `storyboard.vue` 从页面内暂存切换到上述接口。

### 非目标

- 不做单镜重绘（单格 img2img）、超分与局部修改，后续迭代另立设计。
- 不改变分镜组（`shot_groups`）语义；批次是派生视图，不落「批」业务实体，只落生成结果。
- 不涉及画面视频生成与合成导出。

## 4. 总体流程

```
分镜规划(shots) ──► 按 9 镜/批切批（前端派生，批次号 batch_no 从 1 递增）
      │
      ├─ ① 提交生成任务  POST .../storyboard-tasks（batch_no, shot_ids, provider, model, size）
      │       └─ 服务端：提示词目录渲染九宫格提示词 → 调图片模型 → grid_file_id 落库
      ├─ ② 轮询任务      GET .../storyboard-tasks/{task_id}
      ├─ ③ 调整映射      PUT .../storyboards/{batch_no}/cells（cell→shot_id）
      └─ ④ 导入切图      POST .../storyboards/{batch_no}/import
              └─ 服务端：ImageIO 切 3×3 → 每镜一张 storyboard_file_id → 更新 shots
```

## 5. API 设计

所有接口挂项目权限校验（`TaskCenterService.canReadProject` / `canWriteProject`），响应走统一 `ResponseBuilder`。

### 5.1 提交九宫格生成任务

`POST /api/projects/{project_id}/episodes/{episode_id}/storyboard-tasks`

| 参数 | 位置 | 说明 |
| --- | --- | --- |
| `batch_no` | query | 批次号，≥1；服务端按「该批实际镜头集合」校验 |
| `shot_ids` | query | 批内镜头 id 列表（防镜头变动后错批），与当前分镜一致性校验 |
| `provider_config_id` | query | 必填，`api_type=IMAGE` 且启用 |
| `model` | query | 必填，≤200 字符 |
| `size` | query | 默认 `2048x2048`（可选 1024x1024 / 4096x4096） |
| `style_supplement` | query | 可选，风格补充，≤500 字符 |
| `Idempotency-Key` | header | 幂等键，复用 `ProductionTask.idempotencyKey` |

响应 `202`：

```json
{
  "task_id": "123",
  "status": "QUEUED",
  "batch_no": 1,
  "prompt_key": "shot_storyboard_grid",
  "prompt_version_id": 45,
  "template_hash": "..."
}
```

语义与错误：

- `404`：剧集不存在或无权访问。
- `400`：镜头集合与当前剧集不一致、provider 非图片类型、批内镜头数为 0。
- `429` + `Retry-After`：队列满（复用三视图合并的 2 并发 / 16 排队配置起步）。
- 幂等键重复且参数一致时返回既有任务（`existing=true` 语义同三视图合并）。

### 5.2 查询生成任务

`GET /api/projects/{project_id}/episodes/{episode_id}/storyboard-tasks/{task_id}`

响应 `200`：

```json
{
  "task_id": "123",
  "status": "RUNNING",
  "progress": 40,
  "stage": "GENERATING_GRID",
  "batch_no": 1,
  "grid_file_id": "uuid",
  "error_code": null,
  "error_message": null
}
```

`status` 取值复用 `ProductionTaskStatus`（PENDING/QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED）。前端沿用三视图合并的 1s 轮询、180 次上限。

### 5.3 恢复已保存批次

`GET /api/projects/{project_id}/episodes/{episode_id}/storyboards`

响应 `200`：

```json
{
  "list": [
    {
      "batch_no": 1,
      "status": "GENERATED",
      "grid_file_id": "uuid",
      "signed_grid_url": "https://...",
      "cells": [
        { "cell": 0, "shot_id": 101, "sequence_no": 1 },
        { "cell": 1, "shot_id": 102, "sequence_no": 2 }
      ],
      "imported_shot_ids": [101, 102],
      "updated_at": "2026-09-08T21:00:00"
    }
  ]
}
```

前端加载后按 `shot_id` 把批次状态（已生成/已填入、格位映射、网格图 URL）还原进 `states`。

### 5.4 保存格位映射

`PUT /api/projects/{project_id}/episodes/{episode_id}/storyboards/{batch_no}/cells`

请求：

```json
{ "grid_file_id": "uuid", "cells": [{ "cell": 3, "shot_id": 104 }] }
```

响应 `200`：`{ "batch_no": 1, "saved": true }`。校验：cell ∈ [0,8] 且不重复、shot_id 属于该批镜头集合。

### 5.5 导入切图（填入分镜的服务端实现）

`POST /api/projects/{project_id}/episodes/{episode_id}/storyboards/{batch_no}/import`

请求：`{ "cells": [{ "cell": 0, "shot_id": 101 }, ...] }`（缺省则用已保存映射）。

处理：读取 `grid_file_id` → ImageIO 切 3×3（每格宽高各 1/3）→ 每格经 `FileService` 落盘 → 逐镜更新 `shots.storyboard_file_id` → 批次状态置 `IMPORTED`。

响应 `200`：

```json
{ "batch_no": 1, "imported": 9, "files": [{ "shot_id": 101, "file_id": "uuid" }] }
```

幂等：重复导入按 shot 覆盖 `storyboard_file_id`（旧文件保留不删，便于回滚）。

## 6. 数据模型

### 6.1 新表 `episode_storyboard_batches`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | BIGINT PK | |
| `project_id` | BIGINT | 索引 |
| `episode_id` | BIGINT | 联合唯一 `(episode_id, batch_no)` |
| `batch_no` | INT | ≥1 |
| `shot_ids` | JSON | 生成时的镜头 id 快照，一致性校验依据 |
| `grid_file_id` | VARCHAR(64) | 九宫格文件 uuid |
| `provider_config_id` | BIGINT | |
| `model` | VARCHAR(200) | |
| `prompt_key` / `prompt_version_id` / `template_hash` | | 提示词审计三件套 |
| `cell_map` | JSON | `[{cell, shot_id}]` |
| `status` | VARCHAR(20) | `GENERATED` / `IMPORTED` |
| `created_by` | BIGINT | |
| `created_at` / `updated_at` | | |

### 6.2 `shots` 表扩展

- 新列 `storyboard_file_id VARCHAR(64) NULL`：该镜分镜图（九宫格切图）文件。
- 迁移脚本单独评审（遵循任务中心文档约定：本文不直接产出迁移）。
- `ShotController` 的 list/create/update DTO 增加 `storyboard_file_id` 透出；create/update 暂不允许前端直写（仅 import 流程写入）。

### 6.3 任务类型

- 复用 `ProductionTaskType.IMAGE_GENERATION`（若枚举缺失则新增），`inputPayload.prompt_key = "shot_storyboard_grid"`，`inputPayload` 记录 `batch_no`、`shot_ids`、`grid_file_id`，便于任务中心统一展示与恢复。

## 7. 提示词目录

- 新增 prompt key：`shot_storyboard_grid`。
- 变量：`cell_descriptions`（逐镜编号描述列表，服务端由 shot 的 prompt/visual_description/action/景别/运镜拼装）、`episode_title`、`style_supplement`、`cell_count`。
- 系统约束固定写入模板：3×3 网格、左→右上→下顺序、角色/服装/场景/光线一致性、相邻格动作连续、细白分隔线、无文字水印。
- 提交任务时解析版本并记录 `prompt_version_id` + `template_hash`；执行时校验哈希（同三视图合并）。

## 8. 服务端切图实现要点

- 使用 `javax.imageio.ImageIO` 读取 `grid_file_id` 对应文件，按 `width/3 × height/3` 切 9 格；非整除时向下取整。
- 输出 PNG 或 JPEG（quality 0.92）；单格文件命名 `storyboard-{episode_id}-{batch_no}-{cell}.png`。
- 切图后逐格调用 `FileService` 入库并生成签名 URL；仅在全部格子成功后开始更新 shots，失败则清理已产生文件（对齐三视图合并的失败补偿逻辑）。
- 网络无关、纯 CPU 操作，放在任务执行线程内完成，不占用 AI 客户端超时预算。

## 9. 前端接入改动点

`frontend/app/pages/graph/storyboard.vue`：

1. `generateBatch`：由直连 `/ai/images/generations` 改为提交 `storyboard-tasks` + 轮询 5.2，成功后从 `grid_file_id` 换签名 URL；`generateAll` 保持串行批量提交。
2. 新增加载：`onMounted` 调 5.3 恢复 `states`（status/gridUrl/cellBySlot/filledSlots）。
3. `pickSlot` 映射变化后调 5.4 保存（防抖 500ms）。
4. `fillBatch`：改为调 5.5，成功后按返回的每镜 `file_id` 标记已填入；`exportMapping` 保留为离线兜底。
5. 路由与步骤条已就绪（`ProductionSteps` 第 5 步、`ProjectWorkspaceRoute` 映射），无需再改。

## 10. 权限与校验

- 读（5.2/5.3）：`canReadProject`；写（5.1/5.4/5.5）：`canWriteProject`。
- `batch_no` 与 `shot_ids` 必须与当前剧集分镜推导的批次一致，避免镜头增删后错批。
- 生成任务执行前二次校验镜头集合未变（同三视图合并对三张参考图的校验思路）。

## 11. 实施阶段

### 阶段 1：生成任务化与恢复（核心链路）

- 新表 `episode_storyboard_batches` 迁移与实体。
- `StoryboardTaskService`（仿 `CharacterThreeViewMergeTaskService`）：submit/detail/execute/recover。
- `StoryboardController`：5.1、5.2、5.3。
- 提示词目录 `shot_storyboard_grid` 种子版本。
- 前端 `generateBatch`/恢复逻辑切换。

### 阶段 2：映射保存与服务端切图

- 5.4、5.5 接口与切图实现。
- 前端映射防抖保存、`fillBatch` 切换。
- 失败补偿与重复导入测试。

### 阶段 3：贯通下游

- `shots.storyboard_file_id` 列 + DTO 透出。
- 画面视频页（`graph/video.vue`）按镜读取分镜图作为首帧参考/视频输入。
- 任务中心展示 `shot_storyboard_grid` 类型任务的中文名与详情。

## 12. 待确认问题

1. 单镜重绘（对单格 img2img 编辑）是否需要预留接口？建议阶段 2 后按需求另立。
2. 九宫格格间是否允许模型画分隔线，还是要求整图无缝、由前端叠加网格线？当前模板要求细白分隔线。
3. `size` 与上游模型的映射关系（如 `2K` 语义）是否统一走 `AiMediaGenerationService` 现有转换逻辑？
4. 批次大小固定 9，是否需要支持 4（2×2）以适配部分模型的低分辨率上限？
5. 重复导入时旧切图文件保留多久，是否需要清理策略？
