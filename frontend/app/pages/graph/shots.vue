<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-5 text-[#20283b] sm:px-7">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
        <div>
          <p class="m-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee]">04 / 07 · PRODUCTION NODE</p>
          <h1 class="mt-4 text-[30px] font-medium leading-none">分镜规划</h1>
          <p class="mt-3 text-xs text-[#5d52ec]">{{ activeEpisodeLabel }}</p>
          <p class="mt-2 text-[13px] text-[#8a94aa]">按场景把本集拆成分镜脚本组，逐镜记录画面、表演、景别调度与声音。</p>
        </div>
        <div class="flex w-full gap-2 sm:w-auto">
          <n-button :loading="loading" class="!h-10 !rounded-[10px] !border-[#dfe3eb] !px-3 !text-[#687388]" secondary @click="reloadAll">↻ 刷新</n-button>
          <n-button class="!h-10 !rounded-[10px] !border-[#665bff] !px-3 !text-[#5b4ff0]" ghost @click="openGroupModal(null)">＋ 新增分镜组</n-button>
        </div>
      </header>

      <ProductionSteps active="shots" />

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
            <button type="button" class="toolbar-button" title="用 AI 重新生成本集分镜" @click="notifyPending('重新生成')">⚙ 重新生成</button>
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
const blankForm = (): ShotForm => ({ visual_description: '', action: '', shot_group_id: 0, duration_seconds: 3, shot_size: null, camera_angle: '', camera_movement: '', focal_length: '', composition: '', atmosphere: '', dialogue: '', dialogue_type: '无台词', sound_effects: '', characters: [], title: '', location: '', time_of_day: '', interior_exterior: null, props: '', prompt: '', negative_prompt: '', narrative_purpose: '', status: 'DRAFT' })
const form = ref<ShotForm>(blankForm())
const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id))
const episodeId = computed(() => String(route.params.episodeId || activeEpisodeId.value || ''))
const episodeParams = computed(() => { const value = Number(episodeId.value); return Number.isInteger(value) && value > 0 ? { episode_id: value } : undefined })
const shotsPath = computed(() => episodeParams.value ? `/projects/${projectId.value}/episodes/${episodeParams.value.episode_id}/shots` : `/projects/${projectId.value}/shots`)
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
</style>
