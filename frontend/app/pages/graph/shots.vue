<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-5 text-[#20283b] sm:px-7">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
        <div>
          <p class="m-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee]">04 / 07 · PRODUCTION NODE</p>
          <h1 class="mt-4 text-[30px] font-medium leading-none">分镜规划</h1>
          <p class="mt-3 text-xs text-[#5d52ec]">{{ activeEpisodeLabel }}</p>
          <p class="mt-2 text-[13px] text-[#8a94aa]">按场景把本集拆成分镜脚本组；可先根据剧本生成提示词，再逐镜确认画面、表演、调度与声音。</p>
        </div>
        <div class="flex w-full gap-2 sm:w-auto">
          <n-button :loading="loading" class="!h-10 !rounded-[10px] !border-[#dfe3eb] !px-3 !text-[#687388]" secondary @click="reloadAll">↻ 刷新</n-button>
          <n-button class="!h-10 !rounded-[10px] !border-[#665bff] !px-3 !text-[#5b4ff0]" ghost @click="openGroupModal(null)">＋ 新增分镜组</n-button>
        </div>
      </header>

      <ProductionSteps active="shots" :stage="activeEpisode?.currentStage" />

      <section class="overflow-hidden rounded-[14px] border border-[#dfe3eb] bg-white">
        <div class="flex flex-wrap items-center justify-between gap-3 border-b border-[#e8eaf0] px-5 py-3.5">
          <span class="text-sm text-[#8994a8]">共 {{ shots.length }} 个镜头 / {{ groups.length }} 组</span>
          <div class="flex flex-wrap items-center gap-1.5">
            <button type="button" class="toolbar-button toolbar-button-accent" title="用 AI 对全部镜头做批量对话调整" @click="notifyPending('总镜头对话')">💬 总镜头对话</button>
            <button type="button" class="toolbar-button" title="查看/还原本集分镜历史版本" @click="notifyPending('版本历史')">🕐 版本历史</button>
            <button type="button" class="toolbar-button" title="勾选多个镜头进行合并" @click="notifyPending('选择合并')">☑ 选择合并</button>
            <button type="button" class="toolbar-button" title="导出分镜 JSON" @click="exportJson">⬇ JSON</button>
            <button type="button" class="toolbar-button" title="导出分镜 CSV" @click="exportCsv">⬇ CSV</button>
            <button type="button" class="toolbar-button" :disabled="importing || loading" title="从 JSON 或 CSV 导入分镜" @click="importInputRef?.click()">⬆ {{ importing ? '导入中...' : '导入' }}</button>
            <input ref="importInputRef" type="file" accept=".json,.csv,application/json,text/csv" class="hidden" @change="onImportFile">
            <button type="button" class="toolbar-button toolbar-button-accent" title="先根据剧本生成提示词，再生成本集分镜" @click="openAiGeneration">✦ AI 生成分镜</button>
            <button type="button" class="toolbar-button" :disabled="!shots.length" title="使用已确认的镜头提示词生成九宫格分镜图" @click="goStoryboard">▨ 生成分镜图</button>
          </div>
        </div>

        <div v-if="loading" class="space-y-3 px-5 py-5">
          <div v-for="item in 3" :key="item" class="h-16 animate-pulse rounded-[10px] bg-[#f2f3f7]"></div>
        </div>
        <div v-else-if="!sections.length" class="flex min-h-[320px] flex-col items-center justify-center px-6 text-center">
          <span class="flex h-14 w-14 items-center justify-center rounded-[14px] bg-[#f0eeff] text-2xl text-[#6654ee]">▦</span>
          <h3 class="mt-4 text-sm font-semibold text-[#4d5870]">这一集还没有分镜</h3>
          <p class="mt-2 max-w-[300px] text-xs leading-5 text-[#929bad]">先创建一个分镜脚本组（对应一个场景），再把镜头补进组里。</p>
          <div class="mt-4 flex gap-2">
            <n-button class="!rounded-[9px]" @click="openGroupModal(null)">＋ 新增分镜组</n-button>
            <n-button class="!rounded-[9px] !bg-[#6654ee] !text-white" type="primary" @click="openCreate()">＋ 新增镜头</n-button>
          </div>
        </div>
        <div v-else class="space-y-2.5 px-5 py-4">
          <section v-for="section in sections" :key="section.key">
            <div class="group-header" role="button" tabindex="0" @click="toggleExpanded(section.key)" @keydown.enter="toggleExpanded(section.key)">
              <span class="shrink-0 text-[#8994a8] transition-transform" :class="expanded[section.key] ?? true ? 'rotate-90' : ''">›</span>
              <strong class="shrink-0 text-sm font-semibold text-[#273148]">{{ section.name }}</strong>
              <span class="shrink-0 text-xs text-[#8994a8]">{{ rangeLabel(section) }}</span>
              <template v-if="section.sceneLabel">
                <span class="shrink-0 text-xs text-[#c9ced9]">|</span>
                <span class="min-w-0 truncate text-xs font-medium text-[#e87517]">📍 {{ section.sceneLabel }}</span>
              </template>
              <span class="ml-auto flex shrink-0 items-center gap-3 text-xs text-[#8994a8]">
                <span>{{ section.shots.length }} 镜</span>
                <span>🕐 {{ formatSeconds(sectionDuration(section)) }}</span>
              </span>
              <span v-if="section.id != null" class="flex shrink-0 items-center gap-1 opacity-0 transition-opacity group-header:hover:opacity-100" @click.stop>
                <button type="button" class="icon-button" title="编辑分镜组" aria-label="编辑分镜组" @click="openGroupModal(section)">✎</button>
                <button type="button" class="icon-button icon-button-danger" title="删除分镜组（镜头保留为未分组）" aria-label="删除分镜组" @click="removeGroup(section)">⌫</button>
              </span>
            </div>
            <div v-if="expanded[section.key] ?? true" class="mt-2 space-y-2.5">
              <div v-if="!section.shots.length" class="rounded-[10px] border border-dashed border-[#e4e7ee] px-4 py-6 text-center text-xs text-[#b0b6c2]">这一组还没有镜头，点击下方“插入镜头”开始。</div>
              <template v-for="shot in section.shots" :key="shot.id">
                <article class="shot-card">
                  <div class="flex items-start gap-3">
                    <div class="shot-thumb" aria-hidden="true">
                      <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect height="18" rx="2" width="18" x="3" y="3" /><circle cx="9" cy="9" r="2" /><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" /></svg>
                    </div>
                    <div class="min-w-0 flex-1">
                      <div class="flex flex-wrap items-center gap-1.5">
                        <span class="shot-no">#{{ String(shot.sequence_no).padStart(3, '0') }}</span>
                        <span v-if="shot.shot_size" class="chip chip-green">{{ shot.shot_size }}</span>
                        <span v-if="shot.camera_angle" class="chip chip-green">{{ shot.camera_angle }}</span>
                        <span v-if="shot.focal_length" class="chip chip-amber">{{ shot.focal_length }}</span>
                        <span v-if="shot.camera_movement" class="chip chip-green">{{ shot.camera_movement }}</span>
                      </div>
                      <p v-if="shot.composition" class="chip chip-pink mt-1.5 inline-block max-w-full">{{ shot.composition }}</p>
                      <p class="mt-1.5 text-[13px] leading-[1.7] text-[#3c465c]">{{ shot.visual_description || shot.action || '暂无画面描述' }}</p>
                      <p v-if="shot.prompt" class="mt-1.5 text-[11px] leading-5 text-[#8a94aa]"><span class="mr-1 text-[#6654ee]">✦</span>提示词：{{ shot.prompt }}</p>
                      <p v-if="shot.dialogue" class="mt-1.5 text-xs leading-5 text-[#168275]"><span class="mr-1">💬</span><span class="mr-1 rounded bg-[#e5f6f2] px-1.5 py-0.5 text-[10px]">对话</span>{{ shot.dialogue }}</p>
                      <div class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1.5">
                        <span v-if="shot.characters" class="flex min-w-0 items-center gap-1 text-[11px] text-[#8994a8]"><span class="shrink-0" aria-hidden="true">👤</span><span class="truncate">{{ displayList(shot.characters) }}</span></span>
                        <span v-if="shot.props" class="flex min-w-0 items-center gap-1 text-[11px] text-[#8994a8]"><span class="shrink-0" aria-hidden="true">⚔</span><span class="truncate">{{ displayList(shot.props) }}</span></span>
                        <span v-if="shot.atmosphere" class="chip chip-pink">{{ shot.atmosphere }}</span>
                        <span v-if="shot.narrative_purpose" class="chip chip-pink">{{ shot.narrative_purpose }}</span>
                      </div>
                    </div>
                    <div class="flex shrink-0 flex-col items-end gap-2">
                      <span class="text-[11px] text-[#8994a8]">{{ formatSeconds(shot.duration_seconds) }}</span>
                      <div class="flex items-center gap-1">
                        <button type="button" class="icon-button" title="编辑镜头" aria-label="编辑镜头" @click="openEdit(shot)">✎</button>
                        <button type="button" class="icon-button icon-button-danger" title="删除镜头" aria-label="删除镜头" @click="removeShot(shot)">⌫</button>
                      </div>
                    </div>
                  </div>
                </article>
                <div class="flex justify-center py-0.5">
                  <button type="button" class="insert-button" @click="openCreate(section.id, shot.sequence_no)">＋ 插入镜头</button>
                </div>
              </template>
              <div v-if="!section.shots.length" class="flex justify-center py-0.5">
                <button type="button" class="insert-button" @click="openCreate(section.id)">＋ 插入镜头</button>
              </div>
            </div>
          </section>
        </div>

        <div class="flex flex-col gap-3 border-t border-[#e8eaf0] bg-[#fbfbfd] px-5 py-4 sm:flex-row sm:items-center">
          <div class="min-w-0 flex-1"><strong class="text-xs font-semibold text-[#4d5870]">连续性检查</strong><p v-if="continuityIssues.length" class="mt-1 truncate text-[11px] text-[#9a742c]">{{ continuityIssues[0].description }}</p><p v-else class="mt-1 text-[11px] text-[#38a778]">当前集镜头顺序、景别和动作衔接没有待处理问题。</p></div>
          <n-button :disabled="!continuityIssues.length" :loading="resolving" class="!rounded-[8px] !border-[#f0d6a6] !text-[#a87320]" secondary size="small" @click="resolveIssues">一键修复</n-button>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-3 border-t border-[#e8eaf0] px-5 py-3.5 text-sm text-[#687388]">
          <div class="flex flex-wrap items-center gap-6">
            <span>🎞 {{ shots.length }} 个镜头</span>
            <span>📍 {{ sceneTotal }} 个场景</span>
            <span>🕐 预估 {{ formatDuration(totalDuration) }}</span>
          </div>
          <n-button size="small" class="!rounded-[8px] !border-[#dfe3eb]" secondary @click="openCreate()">＋ 追加镜头</n-button>
        </div>
      </section>
    </div>

    <n-modal v-model:show="editorVisible" preset="card" :title="editingShotId == null ? '新增分镜' : `编辑分镜 #${String(editingSequence).padStart(3, '0')}`" class="w-[min(94vw,860px)]" :mask-closable="false">
      <div class="space-y-4">
        <label class="block"><span class="form-label">画面描述</span><n-input v-model:value="form.visual_description" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" placeholder="描述画面中的人物、动作和前中后景关系" /></label>
        <label class="block"><span class="form-label">动作/表演</span><n-input v-model:value="form.action" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" placeholder="记录动作节拍和表演要求" /></label>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block"><span class="form-label">画面生成提示词</span><n-input v-model:value="form.prompt" type="textarea" :autosize="{ minRows: 3, maxRows: 7 }" placeholder="由 AI 根据剧本生成，也可以手动调整" /></label>
          <label class="block"><span class="form-label">反向提示词</span><n-input v-model:value="form.negative_prompt" type="textarea" :autosize="{ minRows: 3, maxRows: 7 }" placeholder="如：模糊、低画质、文字、水印、变形" /></label>
        </div>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block"><span class="form-label">场景</span><n-select v-model:value="form.shot_group_id" :options="sceneOptions" placeholder="选择所属分镜脚本组" /></label>
          <label class="block"><span class="form-label">时长（秒）</span><n-input-number v-model:value="form.duration_seconds" :min="0" :step="0.1" class="w-full" /></label>
        </div>
        <div>
          <span class="form-label">出场角色</span>
          <div class="mt-1.5 flex flex-wrap gap-2">
            <button v-for="option in characterOptions" :key="option" type="button" class="role-chip" :class="{ 'role-chip--active': form.characters.includes(option) }" @click="toggleCharacter(option)">{{ option }}</button>
            <span v-if="!characterOptions.length" class="text-xs text-[#b0b6c2]">当前剧集还没有角色资产，可在资产管理页提取后选择。</span>
          </div>
        </div>
        <div class="grid gap-4 sm:grid-cols-3">
          <label class="block"><span class="form-label">景别</span><n-select v-model:value="form.shot_size" clearable :options="shotSizeOptions" placeholder="选择景别" /></label>
          <label class="block"><span class="form-label">机位角度</span><n-input v-model:value="form.camera_angle" placeholder="例如：位于主角左侧的低机位" /></label>
          <label class="block"><span class="form-label">运镜</span><n-input v-model:value="form.camera_movement" placeholder="固定、推近、横移或其他调度" /></label>
        </div>
        <div class="grid gap-4 sm:grid-cols-3">
          <label class="block"><span class="form-label">焦距</span><n-input v-model:value="form.focal_length" placeholder="24mm" /></label>
          <label class="block"><span class="form-label">构图</span><n-input v-model:value="form.composition" placeholder="前中后景与站位关系" /></label>
          <label class="block"><span class="form-label">氛围</span><n-input v-model:value="form.atmosphere" placeholder="危险临界、冷紫光..." /></label>
        </div>
        <label class="block"><span class="form-label">台词</span><n-input v-model:value="form.dialogue" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" placeholder="台词内容..." /></label>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block"><span class="form-label">台词类型</span><n-select v-model:value="form.dialogue_type" :options="dialogueTypeOptions" /></label>
          <label class="block"><span class="form-label">音效</span><n-input v-model:value="form.sound_effects" placeholder="装甲撕裂声、虫群嘶鸣..." /></label>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button @click="editorVisible = false">取消</n-button>
          <n-button color="#3f4657" type="primary" :loading="saving" @click="saveShot">保存</n-button>
        </div>
      </template>
    </n-modal>

    <n-modal v-model:show="groupModalVisible" preset="card" :title="groupEditingId == null ? '新增分镜组' : '编辑分镜组'" class="w-[min(92vw,480px)]" :mask-closable="false">
      <n-form label-placement="top">
        <n-form-item label="分组名称"><n-input v-model:value="groupForm.name" placeholder="留空则自动命名，如：分镜脚本 S1" maxlength="100" /></n-form-item>
        <n-form-item label="场景标注"><n-input v-model:value="groupForm.scene_label" type="textarea" :rows="2" placeholder="如：克普鲁星区星际战场｜时空跃迁后的战场状态" maxlength="300" show-count /></n-form-item>
      </n-form>
      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button @click="groupModalVisible = false">取消</n-button>
          <n-button color="#3f4657" type="primary" :loading="groupSaving" @click="saveGroup">保存</n-button>
        </div>
      </template>
    </n-modal>

    <n-modal v-model:show="aiModalVisible" preset="card" title="AI 生成分镜" class="w-[min(96vw,900px)]" :mask-closable="false">
      <div class="space-y-4">
        <div class="rounded-[10px] border border-[#e8e5fb] bg-[#fbfaff] px-3.5 py-3">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div>
              <p class="m-0 text-xs font-semibold text-[#353d55]">{{ activeEpisodeLabel }}</p>
              <p class="mt-1.5 mb-0 text-[11px] leading-5 text-[#858da1]">分镜必须以当前剧集剧本为依据。先生成并确认逐镜提示词，再生成可写入的分镜草稿。</p>
            </div>
            <span class="rounded-md bg-[#eceaff] px-2 py-1 text-[10px] font-medium text-[#5d52ec]">{{ aiScriptLoading ? '剧本读取中...' : aiScriptContent ? `${aiScriptContent.length.toLocaleString()} 字剧本` : '暂无剧本' }}</span>
          </div>
          <p v-if="aiScriptError" class="mt-2 mb-0 text-[11px] leading-5 text-[#b45d64]" role="alert">{{ aiScriptError }}</p>
          <p v-else-if="aiScriptContent" class="mt-2 mb-0 text-[11px] leading-5 text-[#7f8799]">将使用当前集最新保存版本作为生成上下文。</p>
          <p v-else class="mt-2 mb-0 text-[11px] leading-5 text-[#b27a38]">请先在剧本页导入并保存当前集剧本。</p>
        </div>

        <div class="grid gap-3 sm:grid-cols-[minmax(0,1fr)_180px]">
          <label class="block"><span class="form-label">生成模型</span><select v-model="aiProviderId" class="ai-control" :disabled="aiGenerating || aiApplying || aiProvidersLoading"><option :value="null">{{ aiProvidersLoading ? '读取模型中...' : aiProviders.length ? '选择对话模型' : '暂无可用对话模型' }}</option><option v-for="provider in aiProviders" :key="provider.id" :value="provider.id">{{ provider.provider }} · {{ provider.model_id }}</option></select></label>
          <label class="block"><span class="form-label">目标镜头数</span><input v-model.number="aiTargetShotCount" type="number" min="1" max="40" class="ai-control" :disabled="aiGenerating || aiApplying"></label>
        </div>
        <label class="block"><span class="form-label">补充要求 <span class="text-[#a3a9b6]">（可选）</span></span><textarea v-model="aiGenerationInstruction" rows="2" maxlength="5000" class="ai-control ai-textarea" :disabled="aiGenerating || aiApplying" placeholder="如：强化结尾悬念，保持人物关系和场景连续性"></textarea></label>

        <div class="ai-stepper" aria-label="AI 分镜生成步骤">
          <button type="button" class="ai-step" :class="aiStep === 1 ? 'ai-step--active' : aiPromptDraft.length ? 'ai-step--done' : ''" @click="aiStep = 1"><span>1</span><strong>生成分镜提示词</strong><small>根据剧本拆分镜头意图</small></button>
          <span class="ai-stepper-line" aria-hidden="true"></span>
          <button type="button" class="ai-step" :class="aiStep === 2 ? 'ai-step--active' : aiShotDraft.length ? 'ai-step--done' : ''" :disabled="!aiPromptDraft.length" @click="aiStep = 2"><span>2</span><strong>生成分镜草稿</strong><small>用提示词补全拍摄字段</small></button>
        </div>

        <section v-if="aiStep === 1" class="rounded-[11px] border border-[#e4e7ee] bg-white p-3.5">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <div><h3 class="m-0 text-xs font-semibold text-[#353d55]">逐镜提示词</h3><p class="mt-1 mb-0 text-[11px] text-[#929bad]">提示词会保留场景、人物动作、构图、光线和叙事目的，生成后仍可逐条修改。</p></div>
            <button type="button" class="toolbar-button toolbar-button-accent" :disabled="aiGenerating || aiApplying || !aiScriptContent.trim() || !selectedAiProvider" @click="generateShotPrompts">{{ aiPromptGenerating ? '生成中...' : aiPromptDraft.length ? '重新生成提示词' : '根据剧本生成提示词' }}</button>
          </div>
          <div v-if="aiPromptError" class="mt-3 rounded-[8px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]" role="alert">{{ aiPromptError }}</div>
          <div v-if="aiPromptDraft.length" class="mt-3 max-h-[390px] space-y-3 overflow-y-auto pr-1">
            <article v-for="(item, index) in aiPromptDraft" :key="`ai-prompt-${index}`" class="rounded-[9px] border border-[#e8eaf0] bg-[#fbfbfd] p-3">
              <div class="flex items-center gap-2"><span class="shot-no">#{{ String(item.sequence_no).padStart(3, '0') }}</span><input v-model="item.scene" class="ai-inline-control min-w-0 flex-1" placeholder="场景 / 分镜组"></div>
              <textarea v-model="item.prompt" rows="3" class="ai-control ai-textarea mt-2" placeholder="画面提示词"></textarea>
              <div class="mt-2 grid gap-2 sm:grid-cols-2"><input v-model="item.negative_prompt" class="ai-inline-control" placeholder="反向提示词"><input v-model="item.narrative_purpose" class="ai-inline-control" placeholder="叙事目的"></div>
            </article>
          </div>
          <div v-else class="mt-4 rounded-[9px] border border-dashed border-[#e1e4ec] px-4 py-7 text-center text-xs text-[#929bad]">点击上方按钮，AI 会从当前剧本生成逐镜提示词。</div>
        </section>

        <section v-else class="rounded-[11px] border border-[#e4e7ee] bg-white p-3.5">
          <div class="flex flex-wrap items-center justify-between gap-2"><div><h3 class="m-0 text-xs font-semibold text-[#353d55]">分镜草稿预览</h3><p class="mt-1 mb-0 text-[11px] text-[#929bad]">生成结果会保留提示词，并补全景别、运镜、动作、台词和声音字段。</p></div><button type="button" class="toolbar-button" :disabled="aiShotGenerating || aiApplying || !aiPromptDraft.length || !selectedAiProvider" @click="generateShotDrafts">{{ aiShotGenerating ? '生成中...' : '重新生成分镜' }}</button></div>
          <div v-if="aiShotError" class="mt-3 rounded-[8px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]" role="alert">{{ aiShotError }}</div>
          <div v-if="aiShotDraft.length" class="mt-3 max-h-[390px] space-y-2.5 overflow-y-auto pr-1">
            <article v-for="(shot, index) in aiShotDraft" :key="`ai-shot-${index}`" class="rounded-[9px] border border-[#e8eaf0] bg-[#fbfbfd] px-3 py-2.5">
              <div class="flex flex-wrap items-center gap-2"><span class="shot-no">#{{ String(shot.sequence_no).padStart(3, '0') }}</span><strong class="min-w-0 flex-1 truncate text-xs text-[#3d465b]">{{ shot.title || '未命名镜头' }}</strong><span v-if="shot.group_name" class="chip chip-pink">{{ shot.group_name }}</span><span class="text-[11px] text-[#8994a8]">{{ formatSeconds(shot.duration_seconds) }}</span><button type="button" class="icon-button" :title="aiEditingShotIndex === index ? '收起镜头编辑' : '编辑镜头草稿'" :aria-label="aiEditingShotIndex === index ? '收起镜头编辑' : '编辑镜头草稿'" @click="toggleAiShotEditor(index)">✎</button></div>
              <p class="mt-1.5 mb-0 text-xs leading-5 text-[#4f596d]">{{ shot.visual_description || shot.action || '暂无画面描述' }}</p>
              <p v-if="shot.prompt" class="mt-1.5 mb-0 text-[11px] leading-5 text-[#8a94aa]">提示词：{{ shot.prompt }}</p>
              <p v-if="shot.dialogue" class="mt-1 mb-0 text-[11px] text-[#168275]">台词：{{ shot.dialogue }}</p>
              <div v-if="aiEditingShotIndex === index" class="mt-3 grid gap-2 border-t border-[#e4e7ee] pt-3 sm:grid-cols-2">
                <label class="block sm:col-span-2"><span class="form-label">镜头标题</span><input v-model="shot.title" class="ai-inline-control" placeholder="镜头标题"></label>
                <label class="block"><span class="form-label">分镜组 / 场景</span><input v-model="shot.group_name" class="ai-inline-control" placeholder="场景名称"></label>
                <label class="block"><span class="form-label">时长（秒）</span><input v-model.number="shot.duration_seconds" type="number" min="0.1" step="0.1" class="ai-inline-control"></label>
                <label class="block"><span class="form-label">景别</span><input v-model="shot.shot_size" class="ai-inline-control" placeholder="景别"></label>
                <label class="block"><span class="form-label">运镜</span><input v-model="shot.camera_movement" class="ai-inline-control" placeholder="运镜方式"></label>
                <label class="block sm:col-span-2"><span class="form-label">画面描述</span><textarea v-model="shot.visual_description" rows="2" class="ai-control ai-textarea"></textarea></label>
                <label class="block sm:col-span-2"><span class="form-label">画面提示词</span><textarea v-model="shot.prompt" rows="3" class="ai-control ai-textarea"></textarea></label>
                <label class="block sm:col-span-2"><span class="form-label">台词</span><input v-model="shot.dialogue" class="ai-inline-control" placeholder="台词内容"></label>
              </div>
            </article>
          </div>
          <div v-else class="mt-4 rounded-[9px] border border-dashed border-[#e1e4ec] px-4 py-7 text-center text-xs text-[#929bad]">先完成提示词生成，再生成分镜草稿。</div>
          <div v-if="aiShotDraft.length" class="mt-3 border-t border-[#e8eaf0] pt-3"><span class="form-label">写入方式</span><div class="flex flex-wrap gap-3 text-xs text-[#687388]"><label class="inline-flex items-center gap-1.5"><input v-model="aiApplyMode" type="radio" value="append">追加到当前分镜</label><label class="inline-flex items-center gap-1.5"><input v-model="aiApplyMode" type="radio" value="replace">替换当前剧集分镜</label></div><p class="mt-1.5 mb-0 text-[10px] leading-4 text-[#929bad]">替换模式会删除当前剧集已有镜头，分镜组会保留。</p></div>
        </section>
      </div>
      <template #footer>
        <div class="flex flex-wrap items-center justify-end gap-2">
          <n-button :disabled="aiGenerating || aiApplying" @click="aiModalVisible = false">取消</n-button>
          <n-button v-if="aiStep === 2" :disabled="aiGenerating || aiApplying || !aiPromptDraft.length" @click="aiStep = 1">返回修改提示词</n-button>
          <n-button v-if="aiStep === 1" type="primary" color="#6654ee" :loading="aiShotGenerating" :disabled="aiGenerating || aiApplying || !aiPromptDraft.length || !selectedAiProvider" @click="generateShotDrafts">根据提示词生成分镜</n-button>
          <n-button v-else type="primary" color="#6654ee" :loading="aiApplying" :disabled="aiGenerating || aiApplying || !aiShotDraft.length" @click="applyAiShots">写入 {{ aiShotDraft.length }} 个分镜</n-button>
        </div>
      </template>
    </n-modal>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'

interface ShotData { id: number; episode_id?: number | null; sequence_no: number; title: string; location?: string | null; time_of_day?: string | null; interior_exterior?: string | null; shot_size?: string | null; camera_movement?: string | null; camera_angle?: string | null; focal_length?: string | null; composition?: string | null; visual_description?: string | null; action?: string | null; dialogue?: string | null; dialogue_type?: string | null; sound_effects?: string | null; characters?: string | null; props?: string | null; prompt?: string | null; negative_prompt?: string | null; atmosphere?: string | null; narrative_purpose?: string | null; duration_seconds: number; status: string; shot_group_id?: number | null; updated_at?: string | null }
interface GroupData { id: number; name: string; scene_label: string | null; sort_order: number }
interface ContinuityIssue { id: number; description: string }
interface ShotsResponse { list: ShotData[]; continuity_issues: ContinuityIssue[]; groups?: GroupData[] }
interface SectionData { key: string; id: number | null; name: string; sceneLabel: string | null; shots: ShotData[] }
interface ShotForm { visual_description: string; action: string; shot_group_id: number; duration_seconds: number; shot_size: string | null; camera_angle: string; camera_movement: string; focal_length: string; composition: string; atmosphere: string; dialogue: string; dialogue_type: string; sound_effects: string; characters: string[]; title: string; location: string; time_of_day: string; interior_exterior: string | null; props: string; prompt: string; negative_prompt: string; narrative_purpose: string; status: string }
interface ChatProvider { id: number; provider: string; api_type?: string; model_id: string; enabled: boolean }
interface ShotPromptDraft { sequence_no: number; scene: string; prompt: string; negative_prompt: string; narrative_purpose: string }
interface AiShotDraft {
  sequence_no: number
  title: string
  group_name: string
  scene_label: string
  location: string
  time_of_day: string
  interior_exterior: string
  shot_size: string
  camera_movement: string
  camera_angle: string
  focal_length: string
  composition: string
  visual_description: string
  action: string
  dialogue: string
  dialogue_type: string
  sound_effects: string
  characters: string
  props: string
  prompt: string
  negative_prompt: string
  atmosphere: string
  narrative_purpose: string
  duration_seconds: number
}

const route = useRoute()
const message = useMessage()
const http = useHttp()
const { activeProject, loadProjects } = useProjectWorkspace()
const { activeEpisodeId, activeEpisode, loadEpisodes } = useEpisodeWorkspace()
const shots = ref<ShotData[]>([])
const groups = ref<GroupData[]>([])
const continuityIssues = ref<ContinuityIssue[]>([])
const characterAssets = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)
const importing = ref(false)
const resolving = ref(false)
const editorVisible = ref(false)
const editingShotId = ref<number | null>(null)
const editingSequence = ref(0)
const expanded = ref<Record<string, boolean>>({})
const groupModalVisible = ref(false)
const groupSaving = ref(false)
const groupEditingId = ref<number | null>(null)
const groupForm = ref({ name: '', scene_label: '' })
const importInputRef = ref<HTMLInputElement | null>(null)
const aiModalVisible = ref(false)
const aiStep = ref<1 | 2>(1)
const aiScriptContent = ref('')
const aiScriptError = ref('')
const aiScriptLoading = ref(false)
const aiProviders = ref<ChatProvider[]>([])
const aiProviderId = ref<number | null>(null)
const aiProvidersLoading = ref(false)
const aiGenerationInstruction = ref('')
const aiTargetShotCount = ref(12)
const aiPromptDraft = ref<ShotPromptDraft[]>([])
const aiShotDraft = ref<AiShotDraft[]>([])
const aiEditingShotIndex = ref<number | null>(null)
const aiPromptGenerating = ref(false)
const aiShotGenerating = ref(false)
const aiApplying = ref(false)
const aiPromptError = ref('')
const aiShotError = ref('')
const aiApplyMode = ref<'append' | 'replace'>('append')
const blankForm = (): ShotForm => ({ visual_description: '', action: '', shot_group_id: 0, duration_seconds: 3, shot_size: null, camera_angle: '', camera_movement: '', focal_length: '', composition: '', atmosphere: '', dialogue: '', dialogue_type: '无台词', sound_effects: '', characters: [], title: '', location: '', time_of_day: '', interior_exterior: null, props: '', prompt: '', negative_prompt: '', narrative_purpose: '', status: 'DRAFT' })
const form = ref<ShotForm>(blankForm())
const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id))
const episodeId = computed(() => String(route.params.episodeId || activeEpisodeId.value || ''))
const episodeParams = computed(() => { const value = Number(episodeId.value); return Number.isInteger(value) && value > 0 ? { episode_id: value } : undefined })
const shotsPath = computed(() => episodeParams.value ? `/projects/${projectId.value}/episodes/${episodeParams.value.episode_id}/shots` : `/projects/${projectId.value}/shots`)
const workflowBase = computed(() => {
  const id = String(projectId.value || '')
  const episode = episodeParams.value?.episode_id
  return `/projects/${id}${episode ? `/episodes/${episode}` : ''}/workflow`
})
const activeEpisodeLabel = computed(() => activeEpisode.value ? `第${String(activeEpisode.value.episodeNo).padStart(3, '0')}集 · ${activeEpisode.value.title}` : '请选择一个剧集')
const totalDuration = computed(() => shots.value.reduce((total, shot) => total + Number(shot.duration_seconds || 0), 0))
const sceneTotal = computed(() => { const labels = new Set(groups.value.map((group) => group.scene_label?.trim()).filter(Boolean)); return labels.size || new Set(shots.value.map((shot) => shot.location?.trim()).filter(Boolean)).size })
const sections = computed<SectionData[]>(() => {
  const sorted = [...shots.value].sort((a, b) => a.sequence_no - b.sequence_no)
  const result: SectionData[] = groups.value.map((group) => ({ key: `g${group.id}`, id: group.id, name: group.name, sceneLabel: group.scene_label, shots: sorted.filter((shot) => shot.shot_group_id === group.id) }))
  const groupedIds = new Set(groups.value.map((group) => group.id))
  const orphans = sorted.filter((shot) => !shot.shot_group_id || !groupedIds.has(shot.shot_group_id))
  if (orphans.length) result.push({ key: 'ungrouped', id: null, name: '未分组', sceneLabel: null, shots: orphans })
  return result
})
const sceneOptions = computed(() => [{ label: '未分组', value: 0 }, ...groups.value.map((group) => ({ label: group.scene_label || group.name, value: group.id }))])
const characterOptions = computed(() => { const merged = new Set(characterAssets.value); shotCharacters(editingSource.value).forEach((name) => merged.add(name)); return [...merged] })
const selectedAiProvider = computed(() => aiProviders.value.find((provider) => provider.id === aiProviderId.value) || null)
const aiGenerating = computed(() => aiPromptGenerating.value || aiShotGenerating.value)
const editingSource = ref<ShotData | null>(null)
const shotSizeOptions = ['大远景', '远景', '全景', '中景', '近景', '特写', '大特写'].map((value) => ({ value, label: value }))
const dialogueTypeOptions = ['无台词', '对白', '旁白'].map((value) => ({ value, label: value }))
function formatSeconds(value: number) { return `${Number(value || 0).toFixed(1)}s` }
function formatDuration(value: number) { const seconds = Math.max(0, Math.round(value || 0)); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
function displayList(value: string) { return value.split(/[，,]/).map((item) => item.trim()).filter(Boolean).join('、') }
function shotCharacters(shot: ShotData | null) { return (shot?.characters || '').split(/[，,]/).map((item) => item.trim()).filter(Boolean) }
function sectionDuration(section: SectionData) { return section.shots.reduce((total, shot) => total + Number(shot.duration_seconds || 0), 0) }
function rangeLabel(section: SectionData) { if (!section.shots.length) return '暂无镜头'; const seq = section.shots.map((shot) => shot.sequence_no); const min = Math.min(...seq); const max = Math.max(...seq); return min === max ? `分镜 ${min}` : `分镜 ${min}-${max}` }
function toggleExpanded(key: string) { expanded.value[key] = !(expanded.value[key] ?? true) }
function toggleCharacter(name: string) { const list = form.value.characters; const index = list.indexOf(name); if (index >= 0) list.splice(index, 1); else list.push(name) }
function notifyPending(feature: string) { message.info(`${feature}能力即将上线`) }
function goStoryboard() { navigateTo(`${workflowBase.value}/storyboard`) }
function resetAiGeneration() {
  aiStep.value = 1
  aiPromptDraft.value = []
  aiShotDraft.value = []
  aiEditingShotIndex.value = null
  aiPromptError.value = ''
  aiShotError.value = ''
  aiApplyMode.value = 'append'
}
function toggleAiShotEditor(index: number) {
  aiEditingShotIndex.value = aiEditingShotIndex.value === index ? null : index
}
async function openAiGeneration() {
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) { message.warning('请先选择项目'); return }
  resetAiGeneration()
  aiModalVisible.value = true
  await Promise.all([loadAiScript(), loadAiProviders()])
}
async function loadAiScript() {
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) return
  aiScriptLoading.value = true
  aiScriptContent.value = ''
  aiScriptError.value = ''
  try {
    const response = await http.get<{ content: string }>(`/projects/${id}/script`, episodeParams.value)
    aiScriptContent.value = response.content || ''
    if (!aiScriptContent.value.trim()) aiScriptError.value = '当前集剧本为空，请先回到剧本页导入内容。'
  } catch (error) {
    aiScriptContent.value = ''
    aiScriptError.value = error instanceof Error ? error.message : '当前集剧本读取失败'
  } finally {
    aiScriptLoading.value = false
  }
}
async function loadAiProviders() {
  aiProvidersLoading.value = true
  try {
    const response = await http.get<{ list: ChatProvider[] }>('/ai/model-providers')
    aiProviders.value = (response.list || []).filter((provider) => provider.enabled && (!provider.api_type || provider.api_type === 'CHAT'))
    if (!selectedAiProvider.value) aiProviderId.value = aiProviders.value[0]?.id || null
  } catch {
    aiProviders.value = []
    aiProviderId.value = null
  } finally {
    aiProvidersLoading.value = false
  }
}
function parseAiJson(raw: string): unknown {
  const cleaned = raw.trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '').trim()
  const candidates = [cleaned]
  const arrayStart = cleaned.indexOf('[')
  const arrayEnd = cleaned.lastIndexOf(']')
  if (arrayStart >= 0 && arrayEnd > arrayStart) candidates.push(cleaned.slice(arrayStart, arrayEnd + 1))
  const objectStart = cleaned.indexOf('{')
  const objectEnd = cleaned.lastIndexOf('}')
  if (objectStart >= 0 && objectEnd > objectStart) candidates.push(cleaned.slice(objectStart, objectEnd + 1))
  for (const candidate of candidates) {
    try { return JSON.parse(candidate) } catch { /* try the next JSON boundary */ }
  }
  throw new Error('AI 未返回可识别的 JSON')
}
function aiResultList(value: unknown, keys: string[]): unknown[] {
  if (Array.isArray(value)) return value
  if (!isRecord(value)) return []
  for (const key of keys) if (Array.isArray(value[key])) return value[key] as unknown[]
  return []
}
function positiveNumber(value: unknown, fallback: number): number {
  const number = Number(value)
  return Number.isFinite(number) && number > 0 ? number : fallback
}
async function requestAiChat(prompt: string) {
  const provider = selectedAiProvider.value
  if (!provider) throw new Error('请选择可用的对话模型')
  const body = new FormData()
  body.append('provider_config_id', String(provider.id))
  body.append('model', provider.model_id)
  body.append('prompt', prompt)
  body.append('stream', 'false')
  return http.post<{ content: string }, FormData>('/ai/chat/completions', body)
}
function normalizeAiPrompt(value: unknown, index: number): ShotPromptDraft | null {
  if (!isRecord(value)) return null
  const prompt = firstImportText(value.prompt, value.image_prompt, value.visual_prompt, value.description, value.visual_description)
  if (!prompt) return null
  return {
    sequence_no: Math.max(1, Math.round(positiveNumber(value.sequence_no ?? value.sequenceNo ?? value.shot_number ?? value.shotNumber, index + 1))),
    scene: firstImportText(value.scene, value.scene_label, value.sceneLabel, value.location, value.sceneName) || '未命名场景',
    prompt,
    negative_prompt: firstImportText(value.negative_prompt, value.negativePrompt, value.negative) || '模糊，低画质，变形，文字，水印，多余肢体，穿模',
    narrative_purpose: firstImportText(value.narrative_purpose, value.narrativePurpose, value.narrative_function, value.narrativeFunction, value.purpose),
  }
}
function normalizeAiShot(value: unknown, index: number): AiShotDraft | null {
  if (!isRecord(value)) return null
  const camera = isRecord(value.camera) ? value.camera : {}
  const dialogue = Array.isArray(value.dialogue) ? importDialogue(value.dialogue) : firstImportText(value.dialogue, value.dialogue_text, value.dialogueText)
  const visualDescription = firstImportText(value.visual_description, value.visualDescription, value.description, value.script_content, value.scriptContent)
  const action = firstImportText(value.action, value.acting, value.performance)
  const prompt = firstImportText(value.prompt, value.image_prompt, value.imagePrompt, value.visual_prompt)
  if (!visualDescription && !action && !prompt) return null
  const scene = firstImportText(value.scene_label, value.sceneLabel, value.scene, value.scene_name, value.sceneName)
  return {
    sequence_no: Math.max(1, Math.round(positiveNumber(value.sequence_no ?? value.sequenceNo ?? value.shot_number ?? value.shotNumber, index + 1))),
    title: firstImportText(value.title, value.shot_title, value.shotTitle) || `镜头 #${index + 1}`,
    group_name: firstImportText(value.group_name, value.groupName, value.scene_name, value.sceneName, scene),
    scene_label: scene,
    location: firstImportText(value.location, value.scene, value.scene_name, value.sceneName, scene),
    time_of_day: firstImportText(value.time_of_day, value.timeOfDay, value.scene_time, value.sceneTime),
    interior_exterior: firstImportText(value.interior_exterior, value.interiorExterior, value.weather_tag, value.weatherTag),
    shot_size: firstImportText(value.shot_size, value.shotSize, value.shot_type, value.shotType, camera.shot_size, camera.shotSize, camera.shotType),
    camera_movement: firstImportText(value.camera_movement, value.cameraMovement, camera.camera_movement, camera.cameraMovement, camera.cameraMove),
    camera_angle: firstImportText(value.camera_angle, value.cameraAngle, camera.camera_angle, camera.cameraAngle),
    focal_length: firstImportText(value.focal_length, value.focalLength, value.camera_focal_length, value.cameraFocalLength, camera.focal_length, camera.focalLength),
    composition: firstImportText(value.composition, camera.composition),
    visual_description: visualDescription,
    action,
    dialogue,
    dialogue_type: importDialogueType(value.dialogue_type ?? value.dialogueType, dialogue) || '无台词',
    sound_effects: firstImportText(value.sound_effects, value.soundEffects, value.sound_effect, value.soundEffect),
    characters: importTextList(value.characters ?? value.character_names ?? value.characterNames),
    props: importTextList(value.props ?? value.prop_names ?? value.propNames),
    prompt,
    negative_prompt: firstImportText(value.negative_prompt, value.negativePrompt) || '模糊，低画质，变形，文字，水印，多余肢体，穿模',
    atmosphere: firstImportText(value.atmosphere, value.mood, value.weather_tag, value.weatherTag, camera.atmosphere),
    narrative_purpose: firstImportText(value.narrative_purpose, value.narrativePurpose, value.narrative_function, value.narrativeFunction, value.purpose),
    duration_seconds: Math.max(0.1, positiveNumber(value.duration_seconds ?? value.durationSeconds ?? value.duration, 3)),
  }
}
async function generateShotPrompts() {
  if (aiPromptGenerating.value || !aiScriptContent.value.trim() || !selectedAiProvider.value) return
  aiPromptGenerating.value = true
  aiPromptError.value = ''
  aiShotDraft.value = []
  const targetCount = Math.min(40, Math.max(1, Math.round(Number(aiTargetShotCount.value) || 12)))
  aiTargetShotCount.value = targetCount
  const script = aiScriptContent.value.slice(0, 180000)
  const prompt = [
    '你是专业影视分镜导演。请严格依据当前剧集剧本，先设计逐镜分镜提示词，不要直接输出完整分镜表。',
    `请规划约 ${targetCount} 个连续镜头，覆盖剧本中的主要动作、人物关系、场景变化和对白节拍；每个镜头必须能被画面生成模型理解。`,
    '只返回 JSON 对象，不要 Markdown、解释或额外文字。结构必须是：{"prompts":[{"sequence_no":1,"scene":"场景或分镜组","prompt":"包含主体、动作、构图、光线、风格的画面提示词","negative_prompt":"反向提示词","narrative_purpose":"该镜头的叙事目的"}]}。',
    '不得添加剧本没有依据的关键人物、事件、地点或关系；可以补充合理的镜头语言，但要保持时间顺序和连续性。',
    aiGenerationInstruction.value.trim() ? `补充要求：${aiGenerationInstruction.value.trim()}` : '',
    `当前剧集：${activeEpisode.value?.title || activeEpisodeLabel.value}`,
    '<script_content>', script, aiScriptContent.value.length > script.length ? '（剧本过长，以上为前 180000 字）' : '', '</script_content>',
  ].filter(Boolean).join('\n')
  try {
    const response = await requestAiChat(prompt)
    const result = parseAiJson(response.content || '')
    const list = aiResultList(result, ['prompts', 'shot_prompts', 'shots', 'list']).map(normalizeAiPrompt).filter((item): item is ShotPromptDraft => Boolean(item)).sort((a, b) => a.sequence_no - b.sequence_no)
    if (!list.length) throw new Error('AI 未返回有效的分镜提示词')
    aiPromptDraft.value = list
    aiStep.value = 1
    message.success(`已生成 ${list.length} 条分镜提示词，请检查后继续`)
  } catch (error) {
    aiPromptError.value = error instanceof Error ? error.message : '分镜提示词生成失败'
  } finally {
    aiPromptGenerating.value = false
  }
}
async function generateShotDrafts() {
  if (aiShotGenerating.value || !aiScriptContent.value.trim() || !aiPromptDraft.value.length || !selectedAiProvider.value) return
  if (aiPromptDraft.value.some((item) => !item.prompt.trim())) { aiShotError.value = '请先补全每条分镜提示词'; return }
  aiShotGenerating.value = true
  aiShotError.value = ''
  const script = aiScriptContent.value.slice(0, 160000)
  const prompt = [
    '你是影视分镜执行编辑。请根据剧本和已经确认的逐镜提示词，生成可以直接写入分镜表的完整镜头数据。',
    '必须保持提示词的镜头顺序和核心画面意图，不要删掉关键对白；只返回 JSON 对象，不要 Markdown、解释或额外文字。',
    '结构必须是：{"shots":[{"title":"镜头标题","group_name":"分镜组名称","scene_label":"场景标注","location":"地点","time_of_day":"时间","interior_exterior":"内景或外景","shot_size":"景别","camera_movement":"运镜","camera_angle":"机位角度","focal_length":"焦距","composition":"构图","visual_description":"画面描述","action":"动作表演","dialogue":"台词","dialogue_type":"无台词/对白/旁白","sound_effects":"音效","characters":["角色"],"props":["道具"],"prompt":"沿用并细化后的画面提示词","negative_prompt":"反向提示词","atmosphere":"氛围","narrative_purpose":"叙事功能","duration_seconds":3}]}。',
    '所有字段都要根据剧本填写；没有台词时 dialogue 写空字符串，characters 和 props 可以为空数组，duration_seconds 使用合理的小数。',
    aiGenerationInstruction.value.trim() ? `补充要求：${aiGenerationInstruction.value.trim()}` : '',
    '<script_content>', script, aiScriptContent.value.length > script.length ? '（剧本过长，以上为前 160000 字）' : '', '</script_content>',
    '<confirmed_shot_prompts>', JSON.stringify(aiPromptDraft.value), '</confirmed_shot_prompts>',
  ].filter(Boolean).join('\n')
  try {
    const response = await requestAiChat(prompt)
    const result = parseAiJson(response.content || '')
    const list = aiResultList(result, ['shots', 'storyboard', 'panels', 'list']).map(normalizeAiShot).filter((item): item is AiShotDraft => Boolean(item)).sort((a, b) => a.sequence_no - b.sequence_no).map((shot, index) => {
      const confirmed = aiPromptDraft.value.find((item) => item.sequence_no === shot.sequence_no) || aiPromptDraft.value[index]
      return {
        ...shot,
        prompt: shot.prompt.trim() || confirmed?.prompt || '',
        negative_prompt: shot.negative_prompt.trim() || confirmed?.negative_prompt || '',
        narrative_purpose: shot.narrative_purpose.trim() || confirmed?.narrative_purpose || '',
        group_name: shot.group_name.trim() || confirmed?.scene || '未命名场景',
        scene_label: shot.scene_label.trim() || confirmed?.scene || '',
      }
    })
    if (!list.length) throw new Error('AI 未返回有效的分镜草稿')
    aiShotDraft.value = list
    aiStep.value = 2
    message.success(`已生成 ${list.length} 个分镜草稿，请确认后写入`)
  } catch (error) {
    aiShotError.value = error instanceof Error ? error.message : '分镜草稿生成失败'
  } finally {
    aiShotGenerating.value = false
  }
}
async function applyAiShots() {
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0 || !aiShotDraft.value.length || aiApplying.value) return
  if (aiShotDraft.value.some((shot) => !shot.prompt.trim())) { aiShotError.value = '请先补全每个分镜的画面提示词'; return }
  if (aiApplyMode.value === 'replace' && shots.value.length && import.meta.client && !window.confirm(`确定替换当前剧集的 ${shots.value.length} 个镜头吗？此操作不可撤销。`)) return
  aiApplying.value = true
  let created = 0
  let failed = 0
  let deleteFailed = 0
  const previousShots = aiApplyMode.value === 'replace' ? [...shots.value] : []
  const remainingDrafts: AiShotDraft[] = []
  try {
    const localGroups = new Map<string, number>()
    for (const group of groups.value) {
      const keys = [group.name, group.scene_label].map((item) => item?.trim()).filter(Boolean) as string[]
      keys.forEach((key) => localGroups.set(key, group.id))
    }
    for (const draft of aiShotDraft.value) {
      try {
        const sceneLabel = draft.scene_label.trim() || draft.location.trim()
        const groupName = draft.group_name.trim() || sceneLabel
        let groupId = localGroups.get(sceneLabel) || localGroups.get(groupName) || null
        if (!groupId && (sceneLabel || groupName)) {
          const group = await http.post<{ id: number }>(`${shotsPath.value}/groups`, { name: groupName.slice(0, 100) || null, scene_label: sceneLabel.slice(0, 300) || null })
          groupId = Number(group.id) || null
          if (groupId) { if (sceneLabel) localGroups.set(sceneLabel, groupId); if (groupName) localGroups.set(groupName, groupId) }
        }
        await http.post(shotsPath.value, {
          title: draft.title.trim() || `镜头 #${created + 1}`,
          location: draft.location.trim() || null,
          time_of_day: draft.time_of_day.trim() || null,
          interior_exterior: draft.interior_exterior.trim() || null,
          shot_size: draft.shot_size.trim() || null,
          camera_movement: draft.camera_movement.trim() || null,
          camera_angle: draft.camera_angle.trim() || null,
          focal_length: draft.focal_length.trim() || null,
          composition: draft.composition.trim() || null,
          visual_description: draft.visual_description.trim() || null,
          action: draft.action.trim() || null,
          dialogue: draft.dialogue.trim() || null,
          dialogue_type: draft.dialogue_type.trim() || '无台词',
          sound_effects: draft.sound_effects.trim() || null,
          characters: draft.characters.trim() || '',
          props: draft.props.trim() || null,
          prompt: draft.prompt.trim() || null,
          negative_prompt: draft.negative_prompt.trim() || null,
          atmosphere: draft.atmosphere.trim() || null,
          narrative_purpose: draft.narrative_purpose.trim() || null,
          duration_seconds: Number(draft.duration_seconds) || 0,
          status: 'DRAFT',
          shot_group_id: groupId,
        })
        created += 1
      } catch { failed += 1; remainingDrafts.push(draft) }
    }
    if (!failed && previousShots.length) {
      for (const shot of previousShots) {
        try { await http.delete(`${shotsPath.value}/${shot.id}`) } catch { deleteFailed += 1 }
      }
    }
    await reloadAll()
    if (failed) {
      aiShotDraft.value = remainingDrafts
      aiApplyMode.value = 'append'
      message.warning(`已写入 ${created} 个分镜，${failed} 个分镜失败；失败项已保留，可修正后重试`)
    }
    else {
      aiModalVisible.value = false
      if (deleteFailed) message.warning(`已写入 ${created} 个分镜，但有 ${deleteFailed} 个旧镜头未删除，请手动检查`)
      else message.success(`已写入 ${created} 个分镜`)
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '分镜写入失败')
  } finally {
    aiApplying.value = false
  }
}
function toForm(shot: ShotData): ShotForm {
  return { visual_description: shot.visual_description || '', action: shot.action || '', shot_group_id: shot.shot_group_id || 0, duration_seconds: Number(shot.duration_seconds || 0), shot_size: shot.shot_size || null, camera_angle: shot.camera_angle || '', camera_movement: shot.camera_movement || '', focal_length: shot.focal_length || '', composition: shot.composition || '', atmosphere: shot.atmosphere || '', dialogue: shot.dialogue || '', dialogue_type: shot.dialogue_type || (shot.dialogue ? '对白' : '无台词'), sound_effects: shot.sound_effects || '', characters: shotCharacters(shot), title: shot.title, location: shot.location || '', time_of_day: shot.time_of_day || '', interior_exterior: shot.interior_exterior || null, props: shot.props || '', prompt: shot.prompt || '', negative_prompt: shot.negative_prompt || '', narrative_purpose: shot.narrative_purpose || '', status: shot.status }
}
function applyResponse(data: ShotsResponse) {
  shots.value = data.list || []
  continuityIssues.value = data.continuity_issues || []
  groups.value = (data.groups || []).slice().sort((a, b) => a.sort_order - b.sort_order)
}
async function loadShots() { const id = projectId.value; if (!Number.isInteger(id) || id <= 0) return; loading.value = true; try { applyResponse(await http.get<ShotsResponse>(shotsPath.value)) } catch (error) { message.error(error instanceof Error ? error.message : '分镜加载失败') } finally { loading.value = false } }
async function loadCharacterAssets() { const id = projectId.value; const episode = episodeParams.value; if (!Number.isInteger(id) || id <= 0 || !episode) { characterAssets.value = []; return }; try { const data = await http.get<{ list: { id: number; name: string; type: string }[] }>(`/projects/${id}/episodes/${episode.episode_id}/assets`); characterAssets.value = (data.list || []).filter((item) => item.type === 'CHARACTER').map((item) => item.name) } catch { characterAssets.value = [] } }
async function reloadAll() { await Promise.all([loadShots(), loadCharacterAssets()]) }
function openCreate(groupId: number | null = null, afterSequence?: number) { editingShotId.value = null; editingSource.value = null; editingSequence.value = afterSequence ? afterSequence + 1 : shots.value.length + 1; form.value = { ...blankForm(), shot_group_id: groupId || 0 }; openEditorWithInsert(afterSequence) }
function openEditorWithInsert(afterSequence?: number) { insertAfter.value = afterSequence ?? null; editorVisible.value = true }
const insertAfter = ref<number | null>(null)
function openEdit(shot: ShotData) { editingShotId.value = shot.id; editingSource.value = shot; editingSequence.value = shot.sequence_no; form.value = toForm(shot); insertAfter.value = null; editorVisible.value = true }
async function saveShot() {
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) return
  if (!form.value.visual_description.trim() && !form.value.action.trim()) { message.warning('请至少填写画面描述或动作表演'); return }
  saving.value = true
  const draft = form.value
  const payload: Record<string, unknown> = {
    title: draft.title.trim() || draft.visual_description.trim().slice(0, 40) || `镜头 #${editingSequence.value}`,
    location: draft.location || null,
    time_of_day: draft.time_of_day || null,
    interior_exterior: draft.interior_exterior,
    shot_size: draft.shot_size,
    camera_movement: draft.camera_movement || null,
    camera_angle: draft.camera_angle || null,
    focal_length: draft.focal_length || null,
    composition: draft.composition || null,
    visual_description: draft.visual_description || null,
    action: draft.action || null,
    dialogue: draft.dialogue || null,
    dialogue_type: draft.dialogue_type || null,
    sound_effects: draft.sound_effects || null,
    characters: draft.characters.join(','),
    props: draft.props || null,
    prompt: draft.prompt || null,
    negative_prompt: draft.negative_prompt || null,
    atmosphere: draft.atmosphere || null,
    narrative_purpose: draft.narrative_purpose || null,
    duration_seconds: Number(draft.duration_seconds) || 0,
    status: draft.status,
    shot_group_id: draft.shot_group_id || null,
  }
  if (editingShotId.value == null && insertAfter.value != null) payload.after_sequence = insertAfter.value
  try {
    if (editingShotId.value == null) await http.post(shotsPath.value, payload)
    else await http.put(`${shotsPath.value}/${editingShotId.value}`, payload)
    const wasNew = editingShotId.value == null
    editorVisible.value = false
    await reloadAll()
    try { await loadEpisodes(String(id)) } catch { /* keep local navigation state */ }
    message.success(wasNew ? '分镜已新增' : '分镜已保存')
  } catch (error) { message.error(error instanceof Error ? error.message : '分镜保存失败') } finally { saving.value = false }
}
async function removeShot(shot: ShotData) { if (import.meta.client && !window.confirm(`确定删除镜头 #${String(shot.sequence_no).padStart(3, '0')} 吗？`)) return; const id = projectId.value; if (!Number.isInteger(id) || id <= 0) return; try { await http.delete(`${shotsPath.value}/${shot.id}`); await loadShots(); message.success('镜头已删除') } catch (error) { message.error(error instanceof Error ? error.message : '镜头删除失败') } }
function openGroupModal(section: SectionData | null) {
  const group = section && section.id != null ? groups.value.find((item) => item.id === section.id) : null
  groupEditingId.value = group?.id ?? null
  groupForm.value = { name: group?.name || '', scene_label: group?.scene_label || '' }
  groupModalVisible.value = true
}
async function saveGroup() {
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) return
  if (!groupForm.value.name.trim() && groupEditingId.value != null) { message.warning('请填写分组名称'); return }
  groupSaving.value = true
  try {
    if (groupEditingId.value == null) await http.post(`${shotsPath.value}/groups`, { name: groupForm.value.name || null, scene_label: groupForm.value.scene_label || null })
    else await http.put(`${shotsPath.value}/groups/${groupEditingId.value}`, { name: groupForm.value.name, scene_label: groupForm.value.scene_label || null })
    groupModalVisible.value = false
    await loadShots()
    message.success('分镜组已保存')
  } catch (error) { message.error(error instanceof Error ? error.message : '分镜组保存失败') } finally { groupSaving.value = false }
}
async function removeGroup(section: SectionData) {
  if (section.id == null) return
  if (import.meta.client && !window.confirm(`确定删除「${section.name}」吗？组内 ${section.shots.length} 个镜头会保留并回到未分组。`)) return
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) return
  try { await http.delete(`${shotsPath.value}/groups/${section.id}`); await loadShots(); message.success('分镜组已删除') } catch (error) { message.error(error instanceof Error ? error.message : '分镜组删除失败') }
}
function downloadFile(filename: string, content: string, type: string) { const blob = new Blob([content], { type }); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = filename; link.click(); URL.revokeObjectURL(url) }
function exportJson() {
  if (!shots.value.length) { message.warning('暂无可导出的分镜'); return }
  const content = JSON.stringify({ episode_id: episodeParams.value?.episode_id ?? null, groups: groups.value, list: shots.value }, null, 2)
  downloadFile(`shots-${episodeParams.value?.episode_id ?? projectId.value}.json`, content, 'application/json')
  message.success('分镜 JSON 已导出')
}
function csvCell(value: unknown) { const text = String(value ?? ''); return `"${text.replace(/"/g, '""')}"` }
function exportCsv() {
  if (!shots.value.length) { message.warning('暂无可导出的分镜'); return }
  const header = ['镜号', '景别', '镜头运动', '机位角度', '焦距', '构图', '时长(s)', '描述', '动作', '原文', '角色', '道具', '对白类型', '对白', '旁白', '场景', '时间', '内外景', '天气', '音效', '氛围', '叙事功能', 'IMAGE PROMPT', 'VIDEO PROMPT', '反向提示词', '状态']
  const rows = shots.value.map((shot) => {
    const dialogueType = shot.dialogue_type || (shot.dialogue ? '对白' : '无台词')
    const narration = dialogueType === '旁白' ? shot.dialogue || '' : ''
    return [shot.sequence_no, shot.shot_size || '', shot.camera_movement || '', shot.camera_angle || '', shot.focal_length || '', shot.composition || '', shot.duration_seconds, shot.visual_description || '', shot.action || '', '', shot.characters || '', shot.props || '', dialogueType, dialogueType === '旁白' ? '' : shot.dialogue || '', narration, shot.location || '', shot.time_of_day || '', shot.interior_exterior || '', '', shot.sound_effects || '', shot.atmosphere || '', shot.narrative_purpose || '', shot.prompt || '', '', shot.negative_prompt || '', shot.status].map(csvCell).join(',')
  })
  downloadFile(`shots-${episodeParams.value?.episode_id ?? projectId.value}.csv`, `${String.fromCharCode(0xFEFF)}${header.map(csvCell).join(',')}\n${rows.join('\n')}`, 'text/csv;charset=utf-8')
  message.success('分镜 CSV 已导出')
}
type JsonRecord = Record<string, unknown>
function isRecord(value: unknown): value is JsonRecord { return typeof value === 'object' && value !== null && !Array.isArray(value) }
function importText(value: unknown): string {
  if (typeof value === 'string') return value.trim()
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}
function firstImportText(...values: unknown[]): string {
  for (const value of values) {
    const text = importText(value)
    if (text) return text
  }
  return ''
}
function importTextList(value: unknown): string {
  if (!Array.isArray(value)) return importText(value).split(/[|｜、，,]/).map((item) => item.trim()).filter(Boolean).join(',')
  return value.map((item) => isRecord(item) ? firstImportText(item.name, item.character, item.content, item.text, item.dialogue, item.line) : importText(item)).filter(Boolean).join(',')
}
function importDialogue(value: unknown): string {
  if (!Array.isArray(value)) return importText(value)
  return value.map((item) => {
    if (!isRecord(item)) return importText(item)
    const line = firstImportText(item.content, item.text, item.dialogue, item.line)
    const speaker = firstImportText(item.character, item.characterName, item.name, item.speaker)
    return line ? (speaker ? `${speaker}：${line}` : line) : ''
  }).filter(Boolean).join('；')
}
function importDialogueType(value: unknown, dialogue: string): string | null {
  const type = importText(value).toLowerCase()
  if (!type) return dialogue ? '对白' : '无台词'
  if (['none', 'no dialogue', '无台词'].includes(type)) return '无台词'
  if (['dialogue', '对白'].includes(type)) return '对白'
  if (['narration', '旁白'].includes(type)) return '旁白'
  return importText(value).slice(0, 20)
}
function importDuration(value: unknown): number {
  const duration = Number(value)
  return Number.isFinite(duration) && duration >= 0 ? duration : 0
}
function importAtmosphere(panel: JsonRecord, camera: JsonRecord): string | null {
  const values = [panel.mood, panel.weatherTag, camera.atmosphere, camera.lighting, camera.colorPalette].map(importText).filter(Boolean)
  return values.length ? values.join('；') : null
}
function parseCsvRows(text: string): string[][] {
  const rows: string[][] = []
  let row: string[] = []
  let cell = ''
  let quoted = false
  const source = text.replace(/^\uFEFF/, '')
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index]
    if (quoted) {
      if (character === '"') {
        if (source[index + 1] === '"') { cell += '"'; index += 1 } else quoted = false
      } else cell += character
    } else if (character === '"' && cell.length === 0) {
      quoted = true
    } else if (character === ',') {
      row.push(cell.trim()); cell = ''
    } else if (character === '\n' || character === '\r') {
      if (character === '\r' && source[index + 1] === '\n') index += 1
      row.push(cell.trim()); cell = ''
      if (row.some((value) => value.length > 0)) rows.push(row)
      row = []
    } else cell += character
  }
  if (cell.length || row.length) { row.push(cell.trim()); if (row.some((value) => value.length > 0)) rows.push(row) }
  return rows
}
function parseCsvPanels(text: string): JsonRecord[] {
  const rows = parseCsvRows(text)
  if (rows.length < 2) return []
  const headers = rows[0].map((header) => header.trim())
  const value = (row: string[], ...names: string[]) => {
    const index = names.map((name) => headers.indexOf(name)).find((item) => item >= 0)
    return index == null ? '' : row[index] || ''
  }
  return rows.slice(1).map((row) => ({
    panelNumber: value(row, '镜号', '序号', 'shot_number', 'panel_number'),
    shotType: value(row, '景别', 'shot_size', 'shotSize'),
    cameraMovement: value(row, '镜头运动', '运镜', 'camera_movement', 'cameraMove'),
    cameraAngle: value(row, '机位角度', 'camera_angle', 'cameraAngle'),
    focalLength: value(row, '焦距', '焦段', 'focal_length', 'focalLength'),
    composition: value(row, '构图', 'composition'),
    duration: value(row, '时长(s)', '时长(秒)', '时长', 'duration_seconds', 'duration'),
    description: value(row, '描述', '画面描述', 'visual_description', 'visualDescription'),
    action: value(row, '动作', 'action'),
    scriptContent: value(row, '原文', 'script_content', 'scriptContent'),
    characterNames: value(row, '角色', '角色名称', 'characters', 'character_names'),
    propNames: value(row, '道具', '道具名称', 'props', 'prop_names'),
    dialogueType: value(row, '对白类型', '台词类型', 'dialogue_type', 'dialogueType'),
    dialogue: value(row, '对白', '台词', 'dialogue'),
    narration: value(row, '旁白', 'narration'),
    sceneName: value(row, '场景', 'scene', 'location', 'scene_name'),
    sceneTime: value(row, '时间', '时段', 'time_of_day', 'timeOfDay'),
    weatherTag: value(row, '天气', 'weather', 'weather_tag'),
    soundEffect: value(row, '音效', '音效说明', 'sound_effects', 'soundEffects'),
    mood: value(row, '氛围', 'atmosphere', 'mood'),
    narrativeFunction: value(row, '叙事功能', '叙事目的', 'narrative_purpose', 'narrativeFunction'),
    imagePrompt: value(row, 'IMAGE PROMPT', 'Image Prompt', '正向提示词', 'prompt'),
    videoPrompt: value(row, 'VIDEO PROMPT', 'Video Prompt', '视频提示词'),
    interiorExterior: value(row, '内外景', 'interior_exterior', 'interiorExterior'),
    negativePrompt: value(row, '反向提示词', 'negative_prompt', 'negativePrompt'),
  }))
}
function importPanelPayload(panel: JsonRecord, groupId: number | null) {
  const camera = isRecord(panel.camera) ? panel.camera : {}
  const visual = firstImportText(panel.description, panel.visualDescription, panel.visual_description, panel.scriptContent, panel.imagePrompt)
  const action = firstImportText(panel.action)
  const dialogue = firstImportText(panel.dialogue, importDialogue(panel.dialogues), panel.narration)
  const scene = firstImportText(panel.sceneName, panel.location, panel.scene_name)
  const weather = importText(panel.weatherTag)
  const titleNumber = firstImportText(panel.shotNumber, panel.panelNumber)
  const title = firstImportText(panel.title, titleNumber ? `镜头 ${titleNumber}` : '', action, visual.slice(0, 40)) || '未命名镜头'
  const characters = firstImportText(importTextList(panel.characterNames), importTextList(panel.characters), isRecord(panel.acting) ? importTextList(panel.acting.characters) : '')
  const props = firstImportText(importTextList(panel.propNames), importTextList(panel.props))
  return {
    title: title.slice(0, 200),
    location: scene || null,
    time_of_day: firstImportText(panel.sceneTime, panel.timeOfDay, panel.time_of_day) || null,
    interior_exterior: firstImportText(panel.interiorExterior, panel.interior_exterior) || (weather.includes('室内') ? '内景' : weather.includes('室外') ? '外景' : null),
    shot_size: firstImportText(panel.shotType, panel.shotSize, panel.shot_size, camera.shotType) || null,
    camera_movement: firstImportText(panel.cameraMovement, panel.cameraMove, panel.camera_movement, camera.cameraMove, camera.camera_movement) || null,
    camera_angle: firstImportText(panel.cameraAngle, panel.camera_angle, camera.cameraAngle, camera.camera_angle) || null,
    focal_length: firstImportText(panel.cameraFocalLength, panel.focalLength, panel.focal_length, camera.focalLength, camera.focal_length) || null,
    composition: firstImportText(panel.composition, camera.composition) || null,
    visual_description: visual || null,
    action: action || null,
    dialogue: dialogue || null,
    dialogue_type: importDialogueType(firstImportText(panel.dialogueType, panel.dialogue_type, panel.narration ? '旁白' : ''), dialogue),
    sound_effects: firstImportText(panel.soundEffect, panel.soundEffects, panel.sound_effects) || null,
    characters: firstImportText(characters, importTextList(panel.character_names)) || null,
    props: firstImportText(props, importTextList(panel.prop_names)) || null,
    prompt: firstImportText(panel.imagePrompt, panel.videoPrompt, panel.prompt) || null,
    negative_prompt: firstImportText(panel.negativePrompt, panel.negative_prompt) || null,
    atmosphere: firstImportText(importAtmosphere(panel, camera), panel.atmosphere) || null,
    narrative_purpose: firstImportText(panel.narrativeFunction, panel.narrativePurpose, panel.narrative_purpose) || null,
    duration_seconds: importDuration(panel.duration ?? panel.duration_seconds),
    status: 'DRAFT',
    shot_group_id: groupId,
  }
}
async function onImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) { input.value = ''; return }
  importing.value = true
  try {
    const fileText = await file.text()
    const isCsv = /\.csv$/i.test(file.name) || file.type.toLowerCase().includes('csv')
    const parsed: unknown = isCsv ? parseCsvPanels(fileText) : JSON.parse(fileText)
    const root = isRecord(parsed) ? parsed : null
    const panelList = root && Array.isArray(root.panels) ? root.panels.filter(isRecord) : []
    const list = panelList.length ? panelList : Array.isArray(parsed) ? parsed.filter(isRecord) : root && Array.isArray(root.list) ? root.list.filter(isRecord) : []
    if (!list.length) { message.warning(`导入文件中没有分镜数据（${isCsv ? '支持 CSV 表头' : '支持 panels 或 list'}）`); return }
    const importedPanels = list.some((item) => 'panelNumber' in item || 'shotNumber' in item || 'sceneName' in item)

    const localGroupIds = new Map<string, number>()
    const findOrCreateGroup = async (sceneLabel: string, name?: string): Promise<number | null> => {
      const key = sceneLabel.trim()
      if (!key) return null
      const cached = localGroupIds.get(key)
      if (cached != null) return cached
      const existing = groups.value.find((group) => group.scene_label?.trim() === key || group.name.trim() === key)
      if (existing) { localGroupIds.set(key, existing.id); return existing.id }
      try {
        const created = await http.post<{ id: number }>(`${shotsPath.value}/groups`, { name: (name || key).slice(0, 100), scene_label: key.slice(0, 300) })
        localGroupIds.set(key, created.id)
        return created.id
      } catch {
        return null
      }
    }

    const importedGroupIds = new Map<string, number>()
    if (root && Array.isArray(root.groups)) {
      for (const group of root.groups.filter(isRecord)) {
        const sceneLabel = firstImportText(group.scene_label, group.name)
        const groupId = await findOrCreateGroup(sceneLabel, firstImportText(group.name) || undefined)
        const sourceId = Number(group.id)
        if (groupId != null && Number.isFinite(sourceId)) importedGroupIds.set(String(sourceId), groupId)
      }
    }
    if (importedPanels) {
      for (const item of list) {
        const sceneLabel = firstImportText(item.sceneName, item.location, item.scene_name)
        await findOrCreateGroup(sceneLabel, `分镜脚本 S${localGroupIds.size + 1}`)
      }
    }

    let imported = 0
    let failed = 0
    for (const item of list) {
      const sourceGroupId = Number(item.shot_group_id)
      const sceneLabel = firstImportText(item.sceneName, item.location, item.scene_name)
      const groupId = importedPanels ? localGroupIds.get(sceneLabel) ?? null : importedGroupIds.get(String(sourceGroupId)) ?? null
      const payload = importPanelPayload(item, groupId)
      try { await http.post(shotsPath.value, payload); imported += 1 } catch { failed += 1 }
    }
    await reloadAll()
    if (!imported) message.error('导入失败：没有镜头成功写入')
    else if (failed) message.warning(`已导入 ${imported}/${list.length} 个镜头，${failed} 个镜头失败`)
    else message.success(`已导入 ${imported} 个镜头${importedPanels ? `，${localGroupIds.size} 个场景组` : ''}`)
  } catch { message.error('导入失败：文件不是有效的 JSON 或 CSV 分镜文件') } finally { input.value = ''; importing.value = false }
}
async function resolveIssues() { const id = projectId.value; if (!Number.isInteger(id) || id <= 0) return; resolving.value = true; try { await http.post(`${shotsPath.value}/continuity/resolve`); await loadShots(); message.success('连续性问题已修复') } catch (error) { message.error(error instanceof Error ? error.message : '连续性问题处理失败') } finally { resolving.value = false } }
async function initialise() { try { await loadProjects() } catch { /* keep cached project */ }; try { await loadEpisodes(String(projectId.value || route.params.projectId || '')) } catch { /* keep local episode */ }; await reloadAll() }
onMounted(initialise)
watch(() => activeProject.value?.id, (next, previous) => { if (next && next !== previous) reloadAll() })
watch(episodeId, (next, previous) => { if (next !== previous) reloadAll() })
</script>

<style scoped>
.toolbar-button { display: inline-flex; align-items: center; gap: 4px; height: 30px; padding: 0 10px; border: 1px solid #dfe3eb; border-radius: 7px; background: #fff; font-size: 12px; font-weight: 500; color: #4d5870; transition: background-color 0.15s, border-color 0.15s, color 0.15s; }
.toolbar-button:hover { background: #f5f6fa; }
.toolbar-button:disabled { cursor: not-allowed; opacity: 0.55; }
.toolbar-button-accent { border-color: #f0c896; color: #e87517; }
.toolbar-button-accent:hover { background: #fff9f1; }
.group-header { display: flex; align-items: center; gap: 8px; width: 100%; padding: 9px 12px; border-radius: 9px; background: #f7f8fa; border: 1px solid transparent; cursor: pointer; text-align: left; transition: background-color 0.15s; }
.group-header:hover { background: #f0f1f5; }
.shot-card { padding: 12px 14px; border: 1px solid #e4e7ee; border-radius: 10px; background: #fff; transition: box-shadow 0.15s; }
.shot-card:hover { box-shadow: 0 8px 24px rgba(46, 51, 76, 0.08); }
.shot-thumb { display: flex; height: 64px; width: 64px; flex-shrink: 0; align-items: center; justify-content: center; border: 1px dashed #d9dee8; border-radius: 8px; background: #fafbfd; color: #c3c9d6; }
.shot-no { font-size: 13px; font-weight: 700; color: #e87517; }
.chip { display: inline-flex; align-items: center; max-width: 100%; padding: 1px 7px; border-radius: 5px; font-size: 11px; line-height: 18px; white-space: nowrap; }
.chip-green { background: #e7f8f0; color: #1f9d6c; }
.chip-amber { background: #fff5e3; color: #b4791d; }
.chip-pink { background: #fdeef2; color: #cf4d7a; }
.role-chip { padding: 3px 12px; border: 1px solid #e4e7ee; border-radius: 999px; background: #fff; font-size: 12px; color: #5c6778; transition: background-color 0.15s, border-color 0.15s, color 0.15s; }
.role-chip:hover { border-color: #b9affc; }
.role-chip--active { border-color: #5b4ff0; background: #5b4ff0; color: #fff; }
.insert-button { display: inline-flex; align-items: center; gap: 3px; height: 26px; padding: 0 12px; border: 1px solid #ffd9b0; border-radius: 999px; background: #fff; font-size: 11px; color: #e87517; transition: background-color 0.15s; }
.insert-button:hover { background: #fff6ec; }
.icon-button { display: inline-flex; height: 26px; width: 26px; align-items: center; justify-content: center; border-radius: 7px; color: #687388; font-size: 14px; transition: background-color 0.15s, color 0.15s; }
.icon-button:hover { background: #f0eeff; color: #5c50dc; }
.icon-button-danger:hover { background: #fff0f0; color: #cf4d58; }
.form-label { display: block; margin-bottom: 6px; font-size: 12px; color: #687388; }
.ai-control { display: block; width: 100%; border: 1px solid #dfe2e8; border-radius: 8px; background: #fff; padding: 8px 10px; font-size: 12px; line-height: 1.5; color: #4b566b; outline: none; transition: border-color 0.15s, box-shadow 0.15s; }
.ai-control:focus { border-color: #9b91f5; box-shadow: 0 0 0 2px #eeecff; }
.ai-control:disabled, .ai-inline-control:disabled { cursor: not-allowed; background: #f6f7f9; }
.ai-textarea { resize: vertical; }
.ai-inline-control { display: block; width: 100%; min-width: 0; border: 1px solid #dfe2e8; border-radius: 7px; background: #fff; padding: 6px 8px; font-size: 11px; line-height: 1.5; color: #4b566b; outline: none; }
.ai-inline-control:focus { border-color: #9b91f5; box-shadow: 0 0 0 2px #eeecff; }
.ai-stepper { display: flex; align-items: stretch; gap: 8px; }
.ai-step { display: grid; grid-template-columns: 24px minmax(0, 1fr); grid-template-rows: auto auto; flex: 1; min-width: 0; gap: 0 8px; padding: 9px 10px; border: 1px solid #e4e7ee; border-radius: 9px; background: #fbfbfd; text-align: left; color: #687388; }
.ai-step > span { grid-row: 1 / span 2; display: flex; height: 24px; width: 24px; align-items: center; justify-content: center; border-radius: 999px; background: #ececf4; font-size: 11px; font-weight: 700; }
.ai-step strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-weight: 600; }
.ai-step small { margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; color: #929bad; }
.ai-step--active { border-color: #bdb5ff; background: #f7f5ff; color: #5d52dd; }
.ai-step--active > span, .ai-step--done > span { background: #6654ee; color: #fff; }
.ai-step--done { border-color: #cde9dd; background: #f5fcf8; color: #278d68; }
.ai-step:disabled { cursor: not-allowed; opacity: 0.55; }
.ai-stepper-line { align-self: center; width: 24px; height: 1px; background: #dfe2e8; }
@media (max-width: 560px) {
  .ai-stepper-line { display: none; }
  .ai-stepper { flex-direction: column; }
}
</style>
