# 画布工作台 ↔ 线性工作流 实体关系与转换设计

> 状态：设计底稿，尚未实现。分支 `feat/canvas-workflow-conversion`。
>
> 本文的实体关系与缺口结论以仓库当前代码为准，每条关键断言附 `file:line`。凡属语义推断而非代码可证的部分，均标注置信度，不当作事实使用。
>
> 标注「已复核」的条目为本次人工逐条 grep/读码确认；其余来自结构化精读，落地前需以代码复核。

## 一、两种表示的本质

### 1.1 画布侧

`GraphWorkflowSnapshot.document`（jsonb）在 Javadoc 中被声明为「恢复工作流的唯一权威来源，其余拆分实体可从中重建」（`GraphWorkflowSnapshot.kt:19-24`），`GraphEntitiesTest.kt:17-85` 把这一假设固化成了实体级断言。

**但运行时事实并非如此**：全仓没有任何服务端投影生成器把 `document` 还原成 `graph_nodes/links/groups/reroutes/subgraphs/subgraph_instances`；`GraphService` 只做逐条 CRUD（`GraphService.kt:100-132`）。前端 `useGraphWorkspace.ts` 只写 nodes/links/groups（`:66-124`），从不调用 `/snapshots`。

- `graph_reroutes` / `graph_subgraphs` / `graph_subgraph_instances` / `graph_workflow_snapshots` 四类投影实体**全仓零写入方**，只有 `GraphController.kt:254,286,344` 的裸端点。（已复核）
- 结论：当前运行时真源是前端逐条写入的 `graph_canvases / graph_nodes / graph_links / graph_groups`；`document` 的「权威」只停留在注释与测试注解里。

另注：`GraphCanvas` 的唯一约束 `(project_id, project_version_id, graph_uuid)` 含可空列（`GraphCanvas.kt:31-33`），而前端创建画布时 `graphUuid` / `projectVersionId` 均为 null（`useGraphWorkspace.ts:38-48`）。Postgres 下 NULL 互不相等，该约束对现行数据实际不生效。

### 1.2 线性侧

真源是 `projects` + `episodes` 两级下的领域表：`script_versions`、`episode_assets` + `character/scene/prop_profiles`、`shots/shot_groups`、`production_tasks/task_events`。

`ProjectVersion` 是**版本维度的空洞**：全仓无 `ProjectVersion(...)` 构造与 `save`，唯一注入点 `GraphService.kt:38` 只做 `findById` 校验；`project.currentVersionId` 从不赋值。（已复核：`grep -rn "ProjectVersion(" src/` 仅命中类声明）

阶段游标由业务 Service 副作用推进，这是线性侧的阶段真值写入点（已复核）：

| 写入点 | 目标阶段 |
|---|---|
| `EpisodeTaskCardService.kt:77` | → `ASSETS` |
| `ShotService.kt:277` | → `SHOTS` |
| `VideoWorkspaceService.kt:71` | → `VIDEO` |
| `ProjectService.kt:138,176` | 显式 setStage / ADVANCE_STAGE |

### 1.3 两边的共享锚点：当前为零

- `GraphService.kt` 全文件不引用 `production_tasks`。
- `ProductionEntities.kt:83-216` 无 `graph_id` / `node_id` / `node_key` 列。
- `GraphNode.kt` 无 `task_id`。

唯一的「桥」是同一 `project` / `episode` 上下文与前端界面语义。此外 `graph_workflow_snapshots.workflow_uuid` 是 `text`，`GraphCanvas.graphUuid` 是 `java.util.UUID`（`GraphCanvas.kt:59`），跨表不可直接比较。

### 1.4 三套互不对齐的阶段集合（转换的核心矛盾）

| 集合 | 值 | 位置 |
|---|---|---|
| 后端 `ProductionStage` | SCRIPT / ASSETS / SHOTS / VIDEO（4 值） | `Project.kt:182-188` |
| 前端步骤条 `steps` | script / tasks / assets / shots / storyboard / video / export（7 步） | `ProductionSteps.vue:48-56` |
| 遗留路由 `legacyStage` | script / tasks / assets / shots / storyboard / video（6 值，无 export） | `middleware/auth.global.ts:33`（已复核） |

后端 4 值无法表达 tasks / storyboard / export。前端用硬编码 `stageCursor = min(level * 2 + 1, steps.length - 1)`（`ProductionSteps.vue:69-72`）补位，导致 **SHOTS（level 2）→ cursor 5，把 index 4 的 `storyboard` 误标为已完成**（已复核推算）。

另有一套**同名不同义的 `stage`**：`ProductionTask.stage`（`ProductionEntities.kt:126-128`）与 `TaskEvent.stage` 指任务执行阶段（`CALL_PROVIDER` / `GENERATING_GRID` 等），与 `ProductionStage` 无关。转换映射必须区分这两者。

### 1.5 既有产品约束（需要先取得放宽）

- `docs/TASK_CENTER_DESIGN.md:42`：「本期不设计通用工作流编排器、DAG 编辑器」
- `docs/AI_PROMPT_MANAGEMENT_DESIGN.md:70`：「本期不实现通用工作流 / DAG 编排器」
- `docs/` 全目录对「画布」零命中，无既有转换设计可继承。

本设计属于**突破既有非目标的新增能力**，动工前需产品层确认。

## 二、实体关系总图

### 2.1 画布侧（聚合根 = `GraphCanvas`）

| 表 | 键 / 约束 | 与聚合根关系 | 写入方 |
|---|---|---|---|
| `graph_canvases` | id PK；唯一 `(project_id, project_version_id, graph_uuid)`（含可空列，实效） | 聚合根，自引用 `parent_graph_id` | 前端 create/update |
| `graph_nodes` | 唯一 `(graph_id, node_key)`；`@Version` | N:1 `graph_id`（逻辑） | 前端 `saveNode` |
| `graph_links` | 唯一 `(graph_id, link_key)` | N:1 `graph_id`；端点 `origin_node_key/target_node_key` + slot 下标 | 前端 `saveLink` |
| `graph_groups` | **无任何唯一约束**，`group_key` 可空可重复 | N:1 `graph_id` | 前端 `addGroup` |
| `graph_reroutes` | 唯一 `(graph_id, reroute_key)` | N:1 `graph_id` | **零写入方** |
| `graph_subgraphs` | 唯一 `(owner_graph_id, subgraph_uuid)`；`canvas_graph_id` 无唯一 | 逻辑指向子图 | **零写入方** |
| `graph_subgraph_instances` | 唯一 `(graph_id, node_key)`；`subgraph_uuid` 可悬空 | N:1 `graph_id` | **零写入方** |
| `graph_workflow_snapshots` | 无唯一；`content_hash` 未加唯一索引 | N:1 `root_graph_id` | **零写入方** |

层次与基数：`GraphCanvas 1—N GraphNode/Link/Group/Reroute/SubgraphInstance`；`GraphCanvas 1—N GraphSubgraph 1—1 child GraphCanvas`（`canvas_graph_id`）。**全部为逻辑关联，无 DB 外键**（实体类无 `@ManyToOne/@OneToMany`，`ddl-auto=update` 不会生成 FK）。级联删除完全由 `GraphService.deleteCanvas` 手工编排（`GraphService.kt:86-98`）。

`GraphSubgraphInstance` 与 `GraphNode` 重复建模了「节点」概念。

### 2.2 生产侧（聚合根 = `Project` / `Episode`）

- `Project 1—N Episode 1—N { ScriptDocument→ScriptVersion、EpisodeAsset→profiles、Shot→ShotGroup、EpisodeTaskCard、ProductionTask }`
- `ScriptVersion 1—N ScriptScene 1—N ScriptDialogue`；`ScriptVersion 1—N ScriptCharacter`；`ScriptVersion 1—N ScriptQualityCheck`
- `ScriptAnalysisResult.task_id ⇄ ProductionTask.id`（1:1 unique，`ScriptEntities.kt:353-427`）
- `CharacterEnrichmentResult.task_id ⇄ ProductionTask.id`（1:1 unique）
- `EpisodeAsset 1—1 CharacterProfile / SceneProfile / PropProfile`（`episode_asset_id` unique）

**声明了却从不写入的断链字段**（转换时必须决定复用或删除）：`ScriptCharacter.assetId/voiceProfileId`、`ScriptDialogue.voiceAssetId`、`Shot.scriptSceneId`、`Shot.previewFileId`、`ShotGroup.scriptSceneId`、`ShotAsset`（全表零写入）、`Asset.currentVersionId`，以及 `AssetVersion` / `AssetLicense` / `VoiceProfile` / `TimelineTrack` / `TimelineClip` / `AiConversation`（零读写）。

### 2.3 文件与任务侧：两套文件模型并存

| 模型 | 表 | 主键 | 被谁引用 |
|---|---|---|---|
| `StoredFile` | `file_metadata` | **UUID** | `shots.storyboard_file_id`、各 `*_profiles.*_file_id`、`episode_assets.audio_file_id`（均为 UUID 字符串） |
| `MediaFile` | `media_files` | **Long** | `production_tasks.output_file_id`、`TimelineClip.media_file_id`、`AssetVersion.media_file_id`（后者零写入） |

`production_tasks.output_file_id` 是 `Long`（`ProductionEntities.kt:147-149`）且**全仓从不写入**，仅在 `TaskCenterController.kt:293` 被读取展示（已复核）。九宫格 / 切图产物实际写进 `input_payload`：

- `input_payload["grid_file_id"]` — `StoryboardTaskService.kt:177`
- `input_payload["cells"]` — `StoryboardTaskService.kt:106`
- `input_payload["imported"]` — `StoryboardTaskService.kt:135`
- 切图 file_id 回填 `shots.storyboard_file_id`（UUID 字符串）— `StoryboardTaskService.kt:131`

而前端任务类型声明的是数字数组 `output_file_ids?: number[]`（`frontend/app/types/tasks.ts:26`）。**UUID ↔ Long 的裂缝必须显式处理**（已复核）。

`TaskEvent`（`TaskEvent.kt:14-54`）无 payload / artifact 引用字段，多文件产物无法在事件流中表达（已复核）。

## 三、实体逐条清单

> 阶段列为该实体主要服务的线性步骤；「画布节点」指声明式目录中语义对应的 `node_type`（**非代码绑定**，见第五节）。

| # | 实体 / 表 | 归属聚合 | 关键字段 | 关系 | 阶段 | 画布节点 |
|---|---|---|---|---|---|---|
| 1 | `GraphCanvas` / `graph_canvases` | GraphCanvas | id, project_id, episode_id(可空), project_version_id(API 可写但无调用方), parent_graph_id, graph_uuid(UUID), graph_kind, revision, graph_version, last_node_id/last_link_id/last_group_id/last_reroute_id, @Version | 自引用 parent；1—N 全部 graph_* | canvas | 全部（容器） |
| 2 | `GraphNode` / `graph_nodes` | GraphCanvas | id, graph_id, node_key, node_key_kind, **node_type(text 自由)**, execution_order, **mode(Int)**, inputs/outputs/properties/widgets_values(jsonb), @Version | N:1 graph_id；唯一(graph_id,node_key) | canvas | 全部 |
| 3 | `GraphLink` / `graph_links` | GraphCanvas | id, graph_id, link_key, origin_node_key, origin_slot(Int), target_node_key, target_slot(Int), floating | N:1 graph_id；唯一(graph_id,link_key) | canvas | 全部连线 |
| 4 | `GraphGroup` / `graph_groups` | GraphCanvas | id, graph_id, group_key(Long? 可空), title, position_x/y, width/height, locked | **无唯一约束** | canvas | 分组 UI |
| 5 | `GraphReroute` / `graph_reroutes` | GraphCanvas | id, graph_id, reroute_key, parent_reroute_key, link_ids(jsonb) | 唯一(graph_id,reroute_key)；零写入 | canvas | 无 |
| 6 | `GraphSubgraph` / `graph_subgraphs` | GraphCanvas | id, owner_graph_id, canvas_graph_id, subgraph_uuid, name, inputs/outputs | 唯一(owner_graph_id,subgraph_uuid)；零写入 | canvas | 无（子图折叠） |
| 7 | `GraphSubgraphInstance` | GraphCanvas | id, graph_id, subgraph_uuid(可悬空), node_key, execution_order, mode | 唯一(graph_id,node_key)；零写入 | canvas | 无 |
| 8 | `GraphWorkflowSnapshot` | GraphCanvas | id, project_id, episode_id, project_version_id, root_graph_id, workflow_uuid(**text**), revision, document(jsonb), content_hash(未唯一) | N:1 root_graph_id；零写入 | canvas | 无 |
| 9 | `GraphNodeCatalog` | 无 | 24 node_type / 8 category；CatalogPort(handle,label,color,required) | 仅 `GraphController.nodeCatalog()` 序列化 | canvas | 节点面板 |
| 10 | `GRAPH_NODE_CATALOG_FALLBACK` | 无 | 24 类型前端镜像（`types/graph.ts:50-275`） | `useGraphWorkspace` catalog 初值 | canvas | 前端回退副本 |
| 11 | `Project` / `projects` | Project | id, code, status, **current_stage**, **script/asset/shot/video_status**, progress, current_version_id(从不写), activities | 1—N episodes | 全阶段 | overview 语义 |
| 12 | `ProjectActivity` | Project | title, stage, createdAt | `@ElementCollection` | 全阶段 | 无 |
| 13 | `ProjectVersion` | Project | id, projectId, episodeId, versionName, status | 被 9 张表的 `project_version_id` 引用（恒 null）；**无创建路径** | — | 无 |
| 14 | `Episode` / `episodes` | Project | id, project_id, episode_no, status, current_stage, progress | N:1 project；无 (project_id,episode_no) 唯一 | 全阶段 | 无 |
| 15 | `ScriptDocument` / `script_documents` | Episode | id, projectId, episodeId(可空), title, currentVersionId | → script_versions | script | `script_input` |
| 16 | `ScriptVersion` / `script_versions` | ScriptDocument | id, scriptDocumentId, projectVersionId(恒 null), versionNo, content, sceneCount/characterCount/dialogueCount, aiAnalysisStatus/Json | N:1 document | script | `script_input` |
| 17 | `ScriptScene` | ScriptVersion | id, scriptVersionId, sceneNo, location, timeOfDay, content, sortOrder | N:1 version | script | `script_input` |
| 18 | `ScriptCharacter` | ScriptVersion | id, scriptVersionId, name, description, motivation, assetId(从不写), voiceProfileId(从不写) | N:1 version | script | `character_extract` |
| 19 | `ScriptDialogue` | ScriptVersion | id, scriptSceneId, characterId, sequenceNo, content, dialogueType, voiceAssetId(从不写) | N:1 scene/character | script | `dubbing_script` |
| 20 | `ScriptQualityCheck` | ScriptVersion | id, scriptVersionId, ruleCode, passed, score | N:1 version | script | 无 |
| 21 | `ScriptAnalysisResult` | ProductionTask | id, taskId(unique), projectId, episodeId, scriptVersionId, contentHash, typeResultsJson, resultJson, resultStatus, adoptedTypes, idempotencyKey | 1:1 production_tasks | script+assets | `*_extract` |
| 22 | `Shot` / `shots` | Episode | id, projectId, episodeId, projectVersionId(恒 null), scriptSceneId(从不写), shotGroupId, sequenceNo, shotSize/cameraMovement/cameraAngle, visualDescription/action/dialogue, characterNames/propNames(逗号文本), prompt/negativePrompt, status, **storyboardFileId(String64→file_metadata UUID)**, previewFileId(从不写) | N:1 shot_group；无 (project,episode,sequence_no) 唯一 | shots, storyboard | `storyboard_plan`/`grid_generate`/`grid_batch`/`image_split`/`shot_video`/`shot_clip` |
| 23 | `ShotGroup` / `shot_groups` | Episode | id, projectId, episodeId, name, sceneLabel, scriptSceneId(从不写), sortOrder | 1—N shots；无唯一；**无 graph 锚点** | shots | 无（对应 `graph_groups`） |
| 24 | `ShotAsset` / `shot_assets` | Shot | id, shotId, assetId, usageType, sortOrder | **全表零写入** | shots | 无 |
| 25 | `ShotContinuityIssue` | Episode | id, projectId, fromShotId, toShotId, issueType, severity, resolved | 无创建路径 | shots | 无 |
| 26 | `EpisodeTaskCard` / `episode_task_cards` | Episode | id, projectId, episodeId(unique), versionNo, primaryGoal, mustInclude/prohibitions(jsonb), previousSummary, nextGoal, endingHook, status | 1:1 episode | tasks | **无节点类型** |
| 27 | `EpisodeTaskCardRevision` | EpisodeTaskCard | id, taskCardId, versionNo, 各约束字段 | N:1 card；可作 tasks 版本锚点 | tasks | 无 |
| 28 | `Asset` / `assets` | Project | id, projectId, name, type, status, currentVersionId(从不写), copyrightStatus | N:1 project；仅项目级端点 | assets | 无（线性实际用 `EpisodeAsset`） |
| 29 | `EpisodeAsset` / `episode_assets` | Episode | id, projectId, episodeId, name, type, description, referenceUrl, **audioFileId(String64)**, notes, status | 被 profiles / Enrichment 引用；无唯一约束 | assets | `*_extract` |
| 30 | `CharacterProfile` | EpisodeAsset | id, episodeAssetId(unique), identityTitle, faceDescription/costumeDescription, threeViewFront/Side/Back/MergedFileId, expressionFileIds, voiceAssetId | 1:1 episode_asset；`*FileId`→file_metadata UUID | assets | `character_art`（产物只落文件，不回填） |
| 31 | `SceneProfile` | EpisodeAsset | id, episodeAssetId(unique), identityTitle/location/timeNote/atmosphere, mainImageFileId, viewEast/South/West/NorthFileId, angle*/light*FileId | 1:1；全部→file_metadata UUID | assets | `scene_art`（同上） |
| 32 | `PropProfile` | EpisodeAsset | id, episodeAssetId(unique), identityTitle, category, plotFunction, conceptImageFileId | 1:1 | assets | `prop_art`（同上） |
| 33 | `AssetVersion` / `asset_versions` | Asset | id, asset_id, projectVersionId(从不写), versionNo, generationPrompt, mediaFileId, status | **全表零读写** | assets | 无 |
| 34 | `AssetLicense` / `AssetTag` / `AssetIssue` | Asset | — | 零读写 | assets | 无 |
| 35 | `VoiceProfile` / `voice_profiles` | Project | id, projectId, characterId, providerConfigId, voiceId/voiceName, sampleFileId, status | 零读写 | assets | `dubbing_script`（无绑定） |
| 36 | `CharacterEnrichmentResult` | ProductionTask | id, taskId(unique), projectId, episodeId, episodeAssetId, scriptVersionId, contentHash, resultJson, resultStatus, idempotencyKey | 1:1 task；N:1 episode_asset | assets | `character_art`（语义相关，无绑定） |
| 37 | `VideoRenderSettings` | Episode | id, projectId, episodeId, projectVersionId(恒 null), aspectRatio/resolution/style/fps, subtitleMode, audioSampleRate | N:1 episode(可空) | video, export | `compose`/`asset_export` |
| 38 | `QualityCheckRun/Item` | Episode | Run(projectId,episodeId,stage,overallScore,passed)；Item(runId,ruleCode,metricValue,thresholdValue,passed) | 1—N；未写入 | — | 无 |
| 39 | `ProjectMember` / `project_members` | Project | id, projectId, userId, role(OWNER/EDITOR/REVIEWER/VIEWER) | 权限校验来源 | — | 转换鉴权需接入 |
| 40 | `TimelineTrack/Clip` | Episode | Track(projectId,episodeId,name,type,sortOrder)；Clip(trackId,mediaFileId,startSeconds,inPoint/outPoint) | 零读写 | — | `compose` |
| 41 | `ProductionStage` / `ProductionNodeStatus` / `ProjectLifecycleAction` | Project | SCRIPT/ASSETS/SHOTS/VIDEO；NOT_STARTED/IN_PROGRESS/NEEDS_ATTENTION/COMPLETED | 存 `projects`/`episodes`.current_stage | 全阶段（关键折叠点） | 全阶段 |
| 42 | `ProductionTask` / `production_tasks` | ProductionTask | id, projectId, episodeId(可空), projectVersionId(恒 null), taskType(10 值), status, progress, **stage(String)**, providerConfigId, modelName, inputPayload(jsonb), **outputFileId(Long, 死字段)**, parentTaskId, idempotencyKey, @Version | 自关联 parent；1—N task_events；被 results 引用；**无 graph 锚点** | 全阶段 | 执行型节点（唯一候选宿主） |
| 43 | `ProductionTaskDependency` | ProductionTask | id, taskId, dependsOnTaskId | **零写入**（已复核）；`TaskCenterService.kt:102` detail 恒空 | 全阶段 | 画布 DAG 落库候选 |
| 44 | `TaskEvent` / `task_events` | ProductionTask | id, taskId, eventType, fromStatus/toStatus, stage, progress, messageCode, correlationId | N:1 task；**无 artifact/payload** | 全阶段 | 节点执行态派生源 |
| 45 | `AiTemplate` / `AiTemplateVersion` | AiTemplate | key, display_name, scope_type, category, version_no, status, messages_json, output_schema_json, content_hash | N:1 template | 全阶段 | 节点参数化来源 |
| 46 | `StoredFile` / **`file_metadata`** | 无 | id **UUID**, ownerId, originalFilename, storedFilename, relativePath, contentType, sizeBytes, sha256, storage | 被全部 UUID 字符串文件字段引用 | 全阶段产物 | 生成型节点 |
| 47 | `MediaFile` / `media_files` | Project | id Long, projectId, episodeId, mediaType, storageKey, url, durationSeconds, width/height | 被 outputFileId / TimelineClip / AssetVersion 逻辑引用；**零写入** | 全阶段 | 无 |
| 48 | `AiModelProviderConfig` | 无 | id, apiType, provider/model 配置 | 按 task_type 选提供商 | 全阶段 | 节点执行需冻结 |

## 四、画布节点 ↔ 线性阶段 映射总表

> **重要前提**：全仓不存在任何 `node_type → (stage, ProductionTaskType, executor)` 的 switch 或注册表。24 个类型字符串除后端目录 `GraphNodeCatalog.kt` 外，只出现在前端镜像 `types/graph.ts` 与 `index.vue:366-372` 的 4 个 demo 节点。服务只按 `task_type` / `prompt_key` 分派，**从不读 `node_type`**（已复核：`grep -rn "node_type\|nodeType" src/main/kotlin/` 仅命中 `GraphNode.kt:48,50` 的列声明与一处无关的 OpenAI 客户端字段）。`GraphNode.node_type` 是自由 text，无目录校验。
>
> 因此下表 `实际执行者` 列除标注为已实现者外**均为人工推断**，转换前必须新建映射表。

| node_type | 类别 | 阶段 | 消费实体 | 产出实体 | 实际执行者 | 置信度 | 证据 |
|---|---|---|---|---|---|---|---|
| `script_input` | input | script | — | `ScriptVersion.content` | `ScriptService.save`（`ScriptController.kt:420`） | high | GraphNodeCatalog.kt:208-216 |
| `image_import` | input | assets（推断） | — | `file_metadata`(UUID) | 无专用实现，仅通用 `POST /api/files` | low | GraphNodeCatalog.kt:217-224 |
| `character_extract` | extract | **script+assets** | ScriptVersion | ScriptAnalysisResult（script 暂存）→ EpisodeAsset（assets 采纳） | `ScriptAnalysisTaskService.submit` → `AssetService.adoptAnalysisAssets` | high | ScriptAnalysisTaskService.kt:164-180; AssetService.kt:381,418 |
| `scene_extract` | extract | **script+assets** | ScriptVersion | ScriptAnalysisResult → EpisodeAsset(SCENE_BACKGROUND) | 同上 | high | ScriptAnalysisTaskService.kt:180 |
| `prop_extract` | extract | **script+assets** | ScriptVersion | ScriptAnalysisResult → EpisodeAsset(PROP) | 同上 | high | AssetService.kt:381-432 |
| `character_art` | art | assets | CharacterProfile | **仅 StoredFile**（不改 Profile） | `AiMediaGenerationService.generateAssetImage`（只读 Profile 构造 prompt，存文件后返回，不回填 `three_view_*`） | medium | AiMediaGenerationService.kt:340-365 |
| `scene_art` | art | assets | SceneProfile | **仅 StoredFile** | `generateAssetImage`（不回填 `view_*`/`angle_*`/`light_*`） | medium | AiMediaGenerationService.kt:368-386; AssetController.kt:283 |
| `prop_art` | art | assets | PropProfile | **仅 StoredFile** | `generateAssetImage`（不写 `concept_image_file_id`） | medium | AssetService.kt:366-379 |
| `storyboard_plan` | storyboard | shots | ScriptVersion, EpisodeAsset | Shot, ShotGroup | **无后端执行器**；前端 shots.vue 走 `/ai/chat/completions` 后 `POST /shots` | medium | shots.vue:509,652,681 |
| `grid_generate` | storyboard | storyboard | Shot | ProductionTask(IMAGE_GENERATION), StoredFile | `StoryboardTaskService.submit`（`prompt_key=shot_storyboard_grid`，每批 9 镜） | high | StoryboardTaskService.kt:48-93,177 |
| `grid_batch` | storyboard | storyboard（**语义歧义**） | Shot | ProductionTask, StoredFile | **无独立执行器**：端口签名与 `grid_generate` 逐字相同，`submit` 只收一个 batchNo，代码无法区分 | low | StoryboardTaskService.kt:48 |
| `image_split` | storyboard | storyboard | ProductionTask, StoredFile | Shot, StoredFile | `StoryboardTaskService.importCells`（ImageIO 切 3×3，回填 `shots.storyboard_file_id`） | high | StoryboardTaskService.kt:112-138 |
| `image_upscale` | storyboard | none | — | — | 无实现 | high | GraphNodeCatalog.kt:338-346 |
| `video_prompt` | storyboard | none | Shot（隐式） | — | 无实现；`REGISTERED_KEYS` 无 `video_prompt` | high | TemplateCatalogService.kt:47-56 |
| `seedance2_prompt` | seedance | none | Shot, Character/Scene/PropProfile | — | 无实现（全仓无 seedance） | high | GraphNodeCatalog.kt:362-378 |
| `seedance2_video` | seedance | **无归属** | — | **不产出 ProductionTask** | 无实现；`VideoWorkspaceService.queue` 与本节点无关 | low | VideoWorkspaceService.kt:54-66 |
| `seedance2_video_single` | seedance | **无归属**（纯推测） | — | — | 无实现 | low | GraphNodeCatalog.kt:393-406 |
| `shot_video` | video | video | Shot | StoredFile（借上游 `POST /api/ai/videos`，不落 production_task） | 无实现 | medium | AiMediaController.kt:162 |
| `video_clip` | video | video | videoPrompts | — | 无实现 | medium | GraphNodeCatalog.kt:422-430 |
| `shot_clip` | video | video | Shot | — | 无实现 | high | GraphNodeCatalog.kt:431-439 |
| `dubbing_script` | audio | none | ScriptVersion（经 scriptText 隐式桥） | — | 无实现 | high | GraphNodeCatalog.kt:446-454 |
| `voice_synthesis` | audio | none | dubbingScript | StoredFile（推测） | 无实现；VOICEOVER/MUSIC/SOUND_EFFECT 只入队不消费 | high | VideoWorkspaceService.kt:64 |
| `compose` | output | export | Shot, ProductionTask, VideoRenderSettings | ProductionTask（无人消费） | 无实现；仅 `POST /video/queue` 建 COMPOSITION | medium | VideoWorkspaceService.kt:54-79 |
| `asset_export` | output | export | Character/Scene/PropProfile | — | 无实现；仅 `FileController /download` | medium | FileController.kt:219 |

按阶段统计：script 1 + 3（extract，跨 script/assets）；assets 由 extract 采纳 + 3 art；shots 1；storyboard 4（含 2 个无实现）；video 3；export 2；无归属 4。

**可执行且已有实现者仅 4 个**：`script_input`、`grid_generate`、`image_split`，以及 `*_extract` 的 submit 半程。

## 五、双向转换的接缝与机制

### 5.1 画布 → 线性

| 接缝 | 机制 | 当前阻碍 |
|---|---|---|
| 节点类型→阶段/任务 | 新建 `graph_node_type_bindings(node_type PK, linear_stage, task_type, prompt_key, executor_bean)`；按 `graph_nodes.node_type` JOIN。executor 只能落成 `ProductionTaskType`(10 值) + `prompt_key`(8 值) 组合 | 无任何映射代码；`node_type` 自由 text；`production_tasks` 无 `node_type` 列 |
| 产物回填 | 通用文件进 `input_payload`；领域字段按列类型分流：UUID 字符串→`shots.storyboard_file_id` / `*_profiles.*_file_id`。须先给 `production_tasks` 加 `graph_id`/`node_key` 锚点，或用 `input_payload["node_key"]` 约定 + 索引 | `output_file_id` 是死字段；两套文件模型 UUID↔Long；无反向锚点 |
| 执行化 | 每个可执行节点建一条 `ProductionTask`，`parentTaskId` 表达画布 DAG，依赖边写 `production_task_dependencies` | 该表零写入；task 无 graph 锚点；`ProductionTaskType` 无视频类 |
| 拓扑→顺序 | 以 `graph_links` DAG 拓扑排序为权威执行序，`execution_order` 仅作展示兜底 | 无 stage 列；无服务端 DAG 执行器 |
| 端口→字段 | 建 `port_handle → 实体字段` 映射（`scriptText`→`script_versions.content`、`characters`→`episode_assets`、`storyboard`→`shots`、`characterImages`→`character_profiles.three_view_*_file_id`） | `GraphLink` 只存 slot 整数下标，**不存 handle**（`GraphLink.kt:52-67`）；前端连线只按下标不比 handle（index.vue:928-930）；同名 handle 颜色不一致（`images` 三色），`seedance2_video` 的 `groups` 既输入又输出 |
| 分组 | `graph_groups` 加 `ref_kind/ref_id` 或映射表指向 `shot_groups`；用 title ↔ name/sceneLabel 匹配 | `graph_groups` 无唯一约束；`shot_groups` 无 graph 锚点；(project,episode,sort_order) 无唯一 |
| 子图折叠 | 二选一：内联展开子图内部节点进父序列；或抽象为单个 `ProductionTask`（`parentTaskId` 指向子任务） | 线性无子图概念；`subgraph_uuid` 可悬空 |
| 归属锚点 | 转换入口显式接收 `(project_id, episode_id)` 并写入双方 | `graph_canvases.episode_id` 可空；线性多数以 `episodes/{episode_id}` 路径隔离，但 script/video 用查询参数 |
| 投影生成器 | 新增 `GraphProjectionService` 解析 `snapshots.document` 生成/更新全部投影并回写 `last_*` | 无投影器；只有 nodes/links/groups 有写入方；`last_*` 只由 `updateCanvas` 客户端整体覆盖，必然漂移 |

### 5.2 线性 → 画布

| 接缝 | 机制 | 当前阻碍 |
|---|---|---|
| 阶段→节点组 | 用 `ProductionStage.entries` 顺序 × 每阶段节点顺序生成 `execution_order` 与串行 link；tasks/storyboard/export 需新增 stage 列或 `graph_canvas_stages` 表 | 后端仅 4 值，前端 7 步；无 stage 列 |
| 实体→节点 | 按反向映射表生成节点；`content`→`script_input`、`shots.sequence_no`→`storyboard_plan`、`*_file_id`→art 节点 | 无反向映射；节点目录与生产表无绑定 |
| 状态回放 | `task_events.toStatus` + `production_tasks.progress` 派生节点运行态 | `GraphNode` 只有 `mode`（配置态），无 `runStatus`/`taskId` |
| 版本锚点 | 把 `ProjectVersion.id` 写入 `graph_canvases.project_version_id` 与 `snapshots.project_version_id` | `ProjectVersion` 无创建路径；`Project.currentVersionId` 从不写；`workflow_uuid`(text) vs `graphUuid`(UUID) 类型不一 |

### 5.3 双向共性

| 接缝 | 机制 | 阻碍 |
|---|---|---|
| 幂等/并发 | 节点自然键 `(canvas_id,node_key)`、连线 `(canvas_id,link_key)` 已有唯一约束；生产侧用 `idempotency_key` 与两张 results 表的唯一约束；新增 `conversion_runs(canvas_id, episode_id, direction, payload_hash, result)` | `saveNode` 以 id 是否为空判定 POST/PUT；`updateCanvas` 全量 19 字段覆盖且**跳过 `entityVersion`**（`GraphService.kt:58-83`）；无 `If-Match`/条件更新；无转换日志 |
| 乐观锁 | 同表示内已有 `@Version entityVersion` | 跨表示无共享键故无冲突检测；前端不回传版本号，detached merge 有 `StaleObjectStateException` 风险 |
| 执行引擎 | MQ 三拓扑（task-dispatch / ai-work / task-events）已定义 | **无 `MqMessageHandler` 实现**（`MqModels.kt:72` 仅接口，已复核）；无服务 publish；各 Service 用私有 `ThreadPoolExecutor` |

## 六、现存缺口清单（按严重度）

1. **无 node_type 映射表/注册表**（严重，转换无法起步）：24 类型仅存于 `GraphNodeCatalog.kt` 与前端镜像 `types/graph.ts`，后端无任何按 `node_type` 分派代码；`production_tasks` 无 `node_type`。（已复核）
2. **两侧无共享锚点列**（严重）：`production_tasks` 无 `graph_id`/`node_id`/`node_key`；`graph_nodes` 无 `task_id`。
3. **产物回填目标错位**（严重）：`output_file_id` 是 Long 死字段，实际写 `input_payload["grid_file_id"/"cells"/"imported"]` 与 `shots.storyboard_file_id`，前端却用数字 `output_file_ids`。（已复核）
4. **无视频任务执行器**（严重）：`ProductionTaskType` 无 VIDEO；`/api/ai/videos` 只返上游 video_id 不落库。所有 video/seedance 节点无法执行化。
5. **阶段集合三套不对齐 + `stageCursor` 误判**（高）：4 vs 7 vs 6；SHOTS 时 `storyboard` 被误标完成。（已复核推算）
6. **美术节点产物无领域回填**（高）：character/scene/prop_art 只产出 StoredFile，`saveCharacterProfile/saveSceneProfile/savePropProfile` 仅被手工调用。唯一自动回填是 `CharacterThreeViewMergeTaskService` → `threeViewMergedFileId`（`AssetService.kt:307`）与 `CharacterEnrichmentTaskService:221`，两者却无对应 `node_type`。`image_split` 回填 `shots.storyboard_file_id` 是唯一已实现的「节点产物 → 领域字段」路径。
7. **目录缺真实执行器对应节点**（高）：`CharacterThreeViewMergeTaskService`、`CharacterEnrichmentTaskService`、`character_expression_image_prompt` 模板均无 `node_type`。
8. **ProjectVersion 空洞**（高）：无创建路径，所有业务表 `project_version_id` 恒 null。（已复核）
9. **投影写入面不完整**（高）：只有 nodes/links/groups 有前端写入；reroutes/subgraphs/subgraph_instances/snapshots 零写入方。
10. **无执行引擎**（高）：MQ 无 handler 实现；`production_task_dependencies` 零写入，`TaskCenterService.kt:102` detail 恒空；`VideoWorkspaceService.kt:64` 建的五类任务全无消费者。
11. **端口 handle 无映射**（中）：`GraphLink` 只存 slot 下标；前端不比 handle。
12. **分组/版本/唯一约束缺失**（中）：`graph_groups` 无唯一；`shot_groups` 无 (project,episode,sort_order) 唯一；`episodes`/`shots`/`episode_assets` 无唯一约束。`script_analysis_results`/`character_enrichment_results` 有幂等唯一——转换幂等键须按表分别对待。
13. **声明了但从不写入的桥字段**（中）：`ScriptCharacter.assetId/voiceProfileId`、`ScriptDialogue.voiceAssetId`、`Shot.scriptSceneId`、`ShotAsset` 全表、`AssetVersion`/`AssetLicense`/`VoiceProfile`/`TimelineTrack`/`AiConversation`。需决定复用或删除。
14. **无可用性标记**（中）：`image_upscale`/`video_prompt`/`seedance2_prompt` 等无实现节点在目录中无禁用位，转换无法区分可执行与占位。
15. **无 schema.sql / 迁移**（中，落地约束）：全仓无 `.sql`；`application.yaml` 的 `ddl-auto=${DB_DDL:update}`，新增锚点列只能靠实体注解自动 DDL；`AGENTS.md` 明确不建迁移脚本。
16. **查询层缺口**（中）：`GraphNodeRepository` 无 `findByNodeType`；`ProductionTaskRepository` 无 graph 锚点查询。
17. **测试零覆盖**（低）：`src/test` 仅 `GraphEntitiesTest.kt`（只断言实体注解）与 `ShotServiceTest.kt`；无转换行为测试。
18. **鉴权 seam 缺失**（低）：转换端点未纳入 `ProjectMember`/roles 校验设计。
19. **架构非目标冲突**（需产品决策）：见 1.5。
20. **双份目录漂移**（低）：后端目录与前端 `GRAPH_NODE_CATALOG_FALLBACK` 两份 24 类型副本，无版本号。

## 七、待决策问题

| # | 问题 | 可选项 | 推荐 |
|---|---|---|---|
| 1 | 画布结构真源方向 | (a) `document` 唯一真源、服务端重建投影（与 Javadoc 一致，改动最大）；(b) `graph_*` 表为真源、快照仅备份（与现状一致）；(c) 双写+仲裁 | **(b)**：现状代码即如此，`document` 权威只停留在 Javadoc，短期成本最低；待投影器建成再迁向 (a) |
| 2 | 转换粒度 | (a) 整画布 ↔ 整项目/剧集 1:1；(b) 单阶段 ↔ 节点组（多对多）；(c) 单节点 ↔ 单 `ProductionTask` | **(c) 为底层、(b) 为 UI 粒度**：节点映射表是唯一可落地的原子 |
| 3 | 项目级画布（`episode_id=null`）如何调和 | (a) 转换必须绑定 `episode_id` 否则拒绝；(b) 映射到 `episodeNo` 最小剧集；(c) 扩展项目级阶段 | **(a)**：线性阶段以 episode 隔离，强制绑定语义最清晰 |
| 4 | tasks/storyboard/export 三步骤后端无 `ProductionStage` | (a) 扩展枚举并迁移硬编码；(b) 维持 4 值仅前端映射；(c) 新建独立 `linear_stage` 表 | **(c)**：不动 `ProductionStage`（避免迁移存量），另建表与画布 stage 列，前端步骤条改为读该表 |
| 5 | 画布是否表达 tasks | (a) 新增 `task_card` 节点类型；(b) 复用 `graph_groups`；(c) 画布不支持 | **(c)**：tasks 是线性独有闸门，无执行产物，强行建模会污染节点目录 |
| 6 | 执行引擎路线 | (a) 复用各 Service 私有线程池 + `production_tasks`（无 MQ）；(b) 接通 MQ 三拓扑实现 `MqMessageHandler`；(c) 新写独立画布执行器 | **(a) 先行**：改动最小且已有 recover 机制；MQ 作为后续演进 |
| 7 | 节点产物存储位置 | (a) `input_payload` + `production_tasks.graph_id/node_key` 锚点列；(b) 新增 `graph_node_artifacts` 表；(c) 塞 `graph_nodes.serialized_payload` | **(b)**：多文件产物（九宫格+切图）在 jsonb 里无序，专门表可查询、可关联两层文件模型；同时补齐 `output_file_id` 的语义（指向 `file_metadata` UUID 还是弃用） |
| 8 | `graph_groups` ↔ `shot_groups` 转换方向 | (a) `graph_groups` 为真源；(b) `shot_groups` 为真源；(c) 独立弱关联 | **(b) + (a) 回填**：分镜组含业务字段（sceneLabel/scriptSceneId），业务真源应在线性侧 |
| 9 | `project_version` 是否启用为转换锚点 | (a) 启用并补创建路径；(b) 承认无版本层级，以 project+episode 为锚；(c) 仅画布 revision 作版本 | **(a) 长期、(b) 短期**：转换先以 project+episode 落地，`ProjectVersion` 接通作为独立工作项 |
| 10 | 音频/export 产品定位 | (a) 归入 video 阶段补执行器；(b) 新增 audio 阶段与 `ProductionStage.AUDIO`；(c) 维持不执行、画布标不可用 | **(c) 短期、(a) 长期**：无 TTS/合成执行器，先明确标不可用，避免转换产生永久 QUEUED 任务 |

## 八、结论与实施顺序

**结论**：画布与线性是**两套完全独立的平行表示**，唯一共享的是 project/episode 上下文与前端界面语义。`graph_workflow_snapshots.document` 的「权威」只在 Javadoc 与测试注解里，运行时真源是逐条 CRUD 的 `graph_canvases/nodes/links/groups`；线性真源是 `projects`/`episodes` 下领域表。节点目录 24 类型与后端实体之间零映射代码，且后端只有 4 个阶段而前端 7 步。

因此双向转换在结构上是**投影**，在数据上是**另存**——建立映射表与锚点列之前，两边没有任何可共享的行。此外画布的 `runNode()` / `runWorkflow()` 目前是纯 UI 桩函数（`index.vue:1018,1164`，只弹 toast，不调后端，已复核），画布现在只是编辑器，没有执行引擎。

**实施顺序（地基优先）**：

1. **冻结并统一映射词表**：新建 `graph_node_type_bindings`，只认一份目录并加版本号，消除前后端双份漂移；同步补 `graph_nodes.stage` 或 `graph_canvas_stages`。
2. **补锚点列**：`production_tasks.graph_id/node_key`（或 `graph_node_artifacts` 表）+ 反向 `graph_nodes.task_id`；修正产物回填通道，明确 `output_file_id` 弃用或改指向 `file_metadata` UUID。
3. **统一阶段枚举**：解决 4 vs 7 vs 6，修正 `stageCursor` 误判，落地 tasks/storyboard/export 的表达。
4. **建投影生成器**：`GraphProjectionService` 解析 `document` 重建投影并回写 `last_*`，同时补齐 reroutes/subgraphs/snapshots 的写入或放弃决策。
5. **接通版本锚点**：补 `ProjectVersion` 创建路径（剧本保存 / 任务卡锁定 / 成片发布时），统一 `workflow_uuid` 与 `graphUuid` 类型。
6. **落地执行引擎**：先复用 `production_tasks` + 依赖边写入；补齐 video 类任务类型与执行器，避免转换产出永久 QUEUED。
7. **幂等与并发**：新增 `conversion_runs` 日志表；`updateCanvas` 加条件更新（回传 `entityVersion`）；生产侧唯一约束按表补齐。
8. **补测试**：转换行为、投影重建、幂等的断言，当前零覆盖。
9. **产品对齐**：先就 `docs/TASK_CENTER_DESIGN.md:42` 的 DAG 编排器非目标取得书面放宽。

## 附：残余不确定

- 各 `node_type` 的「实际执行者」除 `script_input` / `grid_generate` / `image_split` 外均为推断，映射表落地后需以代码重新核实。
- `grid_batch` 与 `grid_generate` 是否应合并（端口逐字相同），需产品确认。
- `video_clip` / `seedance2_video` / `seedance2_video_single` 的阶段归属无代码依据，待映射表显式定义。
- 项目级画布是否允许转换（`episode_id` 可空）需产品决策。
