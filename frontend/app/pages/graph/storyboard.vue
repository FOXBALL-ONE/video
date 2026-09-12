<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-5 text-[#20283b] sm:px-7">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
        <div>
          <p class="m-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee]">05 / 07 · PRODUCTION NODE</p>
          <h1 class="mt-4 text-[30px] font-medium leading-none">分镜图生成</h1>
          <p class="mt-3 text-xs text-[#5d52ec]">{{ activeEpisodeLabel }}</p>
          <p class="mt-2 text-[13px] text-[#8a94aa]">按每 9 个镜头一批生成九宫格连续分镜画板，在裁切预览中把格位映射到镜头后填入，供画面视频阶段使用。</p>
        </div>
        <div class="flex w-full gap-2 sm:w-auto">
          <n-button :loading="loading" class="!h-10 !rounded-[10px] !border-[#dfe3eb] !px-3 !text-[#687388]" secondary @click="reloadAll">↻ 刷新</n-button>
          <n-button class="!h-10 !rounded-[10px] !border-[#dfe3eb] !px-3 !text-[#687388]" secondary @click="openSettings">⚙ 生成设置</n-button>
          <n-button class="!h-10 !rounded-[10px] !bg-[#e87517] !px-3 !text-white" type="primary" color="#e87517" :loading="generatingAll" :disabled="generatingAll || !batches.length" @click="generateAll">✦ 全部生成</n-button>
        </div>
      </header>

      <ProductionSteps active="storyboard" :stage="activeEpisode?.currentStage" />

      <section class="overflow-hidden rounded-[14px] border border-[#dfe3eb] bg-white">
        <div class="flex flex-wrap items-center justify-between gap-3 border-b border-[#e8eaf0] px-5 py-3.5">
          <div class="flex flex-wrap items-center gap-x-5 gap-y-1">
            <span class="text-sm text-[#8994a8]">共 {{ shots.length }} 个镜头 / {{ batches.length }} 批九宫格</span>
            <span class="text-xs text-[#8994a8]">已生成 <strong class="text-[#278d68]">{{ generatedCount }}</strong>/{{ batches.length }}</span>
            <span class="text-xs text-[#8994a8]">已填入 <strong class="text-[#e87517]">{{ filledCount }}</strong>/{{ shots.length }}</span>
          </div>
          <div class="flex flex-wrap items-center gap-1.5">
            <button type="button" class="toolbar-button" :disabled="!filledCount" title="导出九宫格与镜头的格位映射（JSON）" @click="exportMapping">⬇ 导出映射</button>
            <span v-if="!imageModelOptions.length" class="chip chip-amber">尚未配置图片模型，请先在生成设置中选择</span>
          </div>
        </div>

        <div v-if="loading" class="space-y-3 px-5 py-5">
          <div v-for="item in 3" :key="item" class="h-24 animate-pulse rounded-[10px] bg-[#f2f3f7]"></div>
        </div>
        <div v-else-if="!batches.length" class="flex min-h-[320px] flex-col items-center justify-center px-6 text-center">
          <span class="flex h-14 w-14 items-center justify-center rounded-[14px] bg-[#f0eeff] text-2xl text-[#6654ee]">▦</span>
          <h3 class="mt-4 text-sm font-semibold text-[#4d5870]">这一集还没有分镜</h3>
          <p class="mt-2 max-w-[300px] text-xs leading-5 text-[#929bad]">先回到分镜规划页拆分镜头并生成提示词，再来批量生成分镜图。</p>
          <n-button class="mt-4 !rounded-[9px] !bg-[#6654ee] !text-white" type="primary" @click="goStep('shots')">前往分镜规划</n-button>
        </div>
        <div v-else class="space-y-2.5 px-5 py-4">
          <p class="mb-1 rounded-[9px] border border-[#e8e5fb] bg-[#fbfaff] px-3.5 py-2.5 text-[11px] leading-5 text-[#7f8799]">
            每批按镜头顺序生成一张 3×3 九宫格画板：先点击「生成九宫格」，再在右侧裁切预览中检查格位与镜头的映射（点击画板格子、再点击镜头行可重新映射），确认后「填入分镜」。
          </p>

          <section v-for="batch in batches" :key="batch.key" class="overflow-hidden rounded-[11px] border border-[#e4e7ee] bg-white">
            <div class="batch-header" role="button" tabindex="0" @click="toggleExpanded(batch.key)" @keydown.enter="toggleExpanded(batch.key)">
              <span class="shrink-0 text-[#8994a8] transition-transform" :class="expanded[batch.key] ?? batch.index === 1 ? 'rotate-90' : ''">›</span>
              <strong class="shrink-0 text-sm font-semibold text-[#273148]">第{{ batch.index }}批</strong>
              <span class="shrink-0 text-xs text-[#8994a8]">{{ batchRangeLabel(batch) }}</span>
              <template v-if="batchSceneLabel(batch)">
                <span class="shrink-0 text-xs text-[#c9ced9]">|</span>
                <span class="min-w-0 truncate text-xs font-medium text-[#e87517]">📍 {{ batchSceneLabel(batch) }}</span>
              </template>
              <span class="ml-auto flex shrink-0 items-center gap-3 text-xs text-[#8994a8]">
                <span>🕐 {{ formatSeconds(batchDuration(batch)) }}</span>
                <span :class="batchStateClass(batch)">{{ batchStatusLabel(batch) }}</span>
              </span>
            </div>

            <div v-if="expanded[batch.key] ?? batch.index === 1" class="border-t border-[#e8eaf0] px-4 py-4">
              <div class="flex flex-col gap-4 md:flex-row">
                <div class="min-w-0 flex-1">
                  <div class="grid-board aspect-square overflow-hidden rounded-[10px] border-2 border-dashed border-[#e1e4ec] bg-[#fafbfd]">
                    <div v-if="!stateOf(batch).gridUrl" class="flex h-full flex-col items-center justify-center text-center">
                      <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" class="text-[#c3c9d6]"><rect height="18" rx="2" width="18" x="3" y="3" /><path d="M3 9h18" /><path d="M3 15h18" /><path d="M9 3v18" /><path d="M15 3v18" /></svg>
                      <p class="mt-3 mb-3 text-sm text-[#929bad]">待生成九宫格</p>
                      <button type="button" class="generate-button" :disabled="stateOf(batch).status === 'GENERATING' || generatingAll" @click="generateBatch(batch)">
                        <span aria-hidden="true">✦</span>{{ stateOf(batch).status === 'GENERATING' ? '生成中...' : '生成九宫格' }}
                      </button>
                      <p v-if="stateOf(batch).error" class="mt-3 max-w-[320px] text-[11px] leading-5 text-[#b45d64]" role="alert">{{ stateOf(batch).error }}</p>
                    </div>
                    <template v-else>
                      <img :src="stateOf(batch).gridUrl" alt="第 {{ batch.index }} 批九宫格" class="h-full w-full object-cover" :class="stateOf(batch).status === 'GENERATING' ? 'opacity-40' : ''">
                      <div class="absolute inset-0 grid grid-cols-3 grid-rows-3">
                        <button v-for="cell in 9" :key="`cell-${batch.key}-${cell - 1}`" type="button" class="grid-cell" :class="{ 'grid-cell--selected': stateOf(batch).selectedCell === cell - 1 }" :aria-label="`选择第 ${cell} 格`" @click="selectCell(batch, cell - 1)"></button>
                      </div>
                      <div v-if="stateOf(batch).status === 'GENERATING'" class="absolute inset-0 flex items-center justify-center bg-white/60 text-xs text-[#e87517]">正在重新生成...</div>
                    </template>
                  </div>
                  <div class="mt-2 flex flex-wrap items-center justify-between gap-2">
                    <span class="text-[11px] text-[#929bad]">{{ stateOf(batch).gridUrl ? '点击画板中的格子选中格位' : '生成后可逐格映射到镜头' }}</span>
                    <div class="flex items-center gap-1.5">
                      <button v-if="stateOf(batch).gridUrl" type="button" class="toolbar-button" :disabled="stateOf(batch).status === 'GENERATING' || generatingAll" @click="generateBatch(batch)">↻ 重新生成</button>
                      <button v-if="stateOf(batch).gridUrl && batchShotCount(batch)" type="button" class="toolbar-button toolbar-button-accent" :disabled="isFilled(batch)" @click="fillBatch(batch)">{{ isFilled(batch) ? `已填入 ${batchShotCount(batch)}/${batchShotCount(batch)}` : `填入分镜（${batchShotCount(batch)} 镜）` }}</button>
                    </div>
                  </div>
                </div>

                <div class="w-full shrink-0 md:w-48">
                  <p class="mb-2 text-xs font-medium text-[#8994a8]">裁切预览</p>
                  <div class="space-y-1.5">
                    <template v-for="(slot, slotIndex) in batch.slots" :key="`slot-${batch.key}-${slotIndex}`">
                      <div v-if="slot" class="crop-row" :class="{ 'crop-row--filled': isSlotFilled(batch, slotIndex) }" role="button" tabindex="0" @click="pickSlot(batch, slotIndex)" @keydown.enter="pickSlot(batch, slotIndex)">
                        <div class="crop-thumb" :style="cellStyle(stateOf(batch).gridUrl, cellOfSlot(batch, slotIndex))">
                          <svg v-if="!stateOf(batch).gridUrl" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><rect height="18" rx="2" width="18" x="3" y="3" /><circle cx="9" cy="9" r="2" /><path d="m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21" /></svg>
                        </div>
                        <div class="min-w-0 flex-1">
                          <span class="text-xs font-medium">#{{ String(slot.sequence_no).padStart(3, '0') }}</span>
                          <p class="truncate text-[10px] leading-4 text-[#8994a8]">{{ shotBrief(slot) }}</p>
                        </div>
                        <span class="fill-dot" :class="{ 'fill-dot--on': isSlotFilled(batch, slotIndex), 'fill-dot--picked': stateOf(batch).selectedCell === cellOfSlot(batch, slotIndex) }"></span>
                      </div>
                      <div v-else class="flex items-center gap-2 opacity-30">
                        <div class="crop-thumb"></div>
                        <span class="text-xs text-[#8994a8]">空位</span>
                      </div>
                    </template>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </div>

        <div class="flex flex-wrap items-center justify-between gap-3 border-t border-[#e8eaf0] bg-[#fbfbfd] px-5 py-3.5 text-sm text-[#687388]">
          <div class="flex flex-wrap items-center gap-6">
            <span>▦ {{ batches.length }} 批九宫格</span>
            <span>已生成 {{ generatedCount }}/{{ batches.length }}</span>
            <span>已填入 {{ filledCount }}/{{ shots.length }}</span>
          </div>
          <span class="text-[11px] text-[#929bad]">映射与切图结果已保存，可刷新页面继续处理</span>
        </div>
      </section>

      <div class="mt-4 flex items-center justify-between">
        <n-button class="!h-10 !rounded-[10px] !border-[#dfe3eb] !px-4 !text-[#687388]" secondary @click="goStep('shots')">‹ 上一步 · 分镜规划</n-button>
        <n-button class="!h-10 !rounded-[10px] !bg-[#e87517] !px-4 !text-white" type="primary" color="#e87517" @click="goStep('video')">下一步 · 画面视频 ›</n-button>
      </div>
    </div>

    <n-modal v-model:show="settingsVisible" preset="card" title="九宫格生成设置" class="w-[min(94vw,560px)]" :mask-closable="false">
      <div class="space-y-4">
        <label class="block">
          <span class="form-label">图片模型</span>
          <select v-model="settingsForm.modelKey" class="ai-control">
            <option :value="''">{{ imageModelOptions.length ? '选择图片模型' : '暂无可用图片模型，请先在 API 页配置' }}</option>
            <option v-for="option in imageModelOptions" :key="option.value" :value="option.value">{{ option.label }}</option>
          </select>
        </label>
        <div class="grid gap-4 sm:grid-cols-2">
          <label class="block">
            <span class="form-label">画板尺寸</span>
            <select v-model="settingsForm.size" class="ai-control">
              <option value="1024x1024">1024×1024（1K，速度快）</option>
              <option value="2048x2048">2048×2048（2K，推荐）</option>
              <option value="4096x4096">4096×4096（4K，单格更清晰）</option>
            </select>
          </label>
          <label class="block">
            <span class="form-label">格位映射</span>
            <input class="ai-control" value="按镜头顺序自动映射（可手动调整）" disabled>
          </label>
        </div>
        <label class="block">
          <span class="form-label">画面风格补充 <span class="text-[#a3a9b6]">（可选，追加到每批提示词末尾）</span></span>
          <textarea v-model="settingsForm.styleSupplement" rows="3" class="ai-control ai-textarea" placeholder="如：赛博朋克科幻风格，冷紫与橙色对比光，电影级构图，高细节"></textarea>
        </label>
        <p class="mb-0 rounded-[9px] bg-[#fbfbfd] px-3 py-2.5 text-[11px] leading-5 text-[#929bad]">
          每批提示词会自动合并批内镜头的提示词/画面描述、景别与运镜，并要求模型保持角色形象、场景与光线在九格之间连续一致。
        </p>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button @click="settingsVisible = false">取消</n-button>
          <n-button color="#3f4657" type="primary" @click="saveSettings">保存</n-button>
        </div>
      </template>
    </n-modal>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'

interface ShotData { id: number; sequence_no: number; visual_description?: string | null; action?: string | null; prompt?: string | null; negative_prompt?: string | null; shot_size?: string | null; camera_movement?: string | null; characters?: string | null; location?: string | null; duration_seconds: number; shot_group_id?: number | null }
interface GroupData { id: number; name: string; scene_label: string | null }
interface ShotsResponse { list: ShotData[]; groups?: GroupData[] }
interface ProviderConfig { id: number; provider: string; api_type?: string; model_id: string; enabled: boolean }
interface GeneratedImageFile { id: string; file_name: string; signed_download_url: string }
interface GeneratedImageResponse { list?: GeneratedImageFile[] }
interface StoryboardTaskResponse { task_id: string; status: string; batch_no: number; grid_file_id?: string; signed_grid_url?: string; progress?: number; error_message?: string }
interface StoryboardListResponse { list: Array<{ batch_no: number; status: string; grid_file_id?: string; signed_grid_url?: string; cells?: Array<{ cell: number; shot_id: number }>; imported_shot_ids?: number[] }> }
interface BatchSlots { key: string; index: number; slots: (ShotData | null)[] }
type BatchStatus = 'PENDING' | 'GENERATING' | 'GENERATED'
interface BatchState { status: BatchStatus; gridUrl: string | null; gridFileId: string | null; error: string; selectedCell: number | null; cellBySlot: number[]; filledSlots: number[] }

const CELL_COUNT = 9
const route = useRoute()
const message = useMessage()
const http = useHttp()
const runtimeConfig = useRuntimeConfig()
const { activeProject, loadProjects } = useProjectWorkspace()
const { activeEpisodeId, activeEpisode, loadEpisodes } = useEpisodeWorkspace()

const shots = ref<ShotData[]>([])
const groups = ref<GroupData[]>([])
const providers = ref<ProviderConfig[]>([])
const loading = ref(false)
const generatingAll = ref(false)
const expanded = ref<Record<string, boolean>>({})
const states = reactive<Record<string, BatchState>>({})
const settingsVisible = ref(false)
const settingsForm = reactive({ modelKey: '', size: '2048x2048', styleSupplement: '' })

const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id))
const episodeId = computed(() => String(route.params.episodeId || activeEpisodeId.value || ''))
const episodeParams = computed(() => { const value = Number(episodeId.value); return Number.isInteger(value) && value > 0 ? { episode_id: value } : undefined })
const shotsPath = computed(() => episodeParams.value ? `/projects/${projectId.value}/episodes/${episodeParams.value.episode_id}/shots` : `/projects/${projectId.value}/shots`)
const activeEpisodeLabel = computed(() => activeEpisode.value ? `第${String(activeEpisode.value.episodeNo).padStart(3, '0')}集 · ${activeEpisode.value.title}` : '请选择一个剧集')
const imageModelOptions = computed(() => providers.value
  .filter((provider) => provider.enabled && (provider.api_type || 'CHAT') === 'IMAGE' && provider.model_id?.trim())
  .map((provider) => ({ label: `${provider.provider} · ${provider.model_id}`, value: `${provider.id}::${provider.model_id}` })))

const batches = computed<BatchSlots[]>(() => {
  const sorted = [...shots.value].sort((a, b) => a.sequence_no - b.sequence_no)
  const result: BatchSlots[] = []
  for (let start = 0; start < sorted.length; start += CELL_COUNT) {
    const chunk: (ShotData | null)[] = sorted.slice(start, start + CELL_COUNT)
    while (chunk.length < CELL_COUNT) chunk.push(null)
    const first = chunk[0]
    result.push({ key: `b${first ? first.id : start}`, index: result.length + 1, slots: chunk })
  }
  return result
})
const generatedCount = computed(() => batches.value.filter((batch) => stateOf(batch).status === 'GENERATED').length)
const filledCount = computed(() => batches.value.reduce((total, batch) => total + stateOf(batch).filledSlots.length, 0))

const imageApiOrigin = (() => {
  const apiBase = String(runtimeConfig.public.apiBase || '').trim()
  if (!apiBase) return ''
  try {
    return new URL(apiBase, import.meta.client ? window.location.origin : 'http://localhost').origin
  } catch {
    return ''
  }
})()

/** Signed file links may carry a stale host; keep the path and rebase API files onto the configured origin. */
function resolveImageUrl(url: string | null | undefined): string | null {
  const value = url?.trim()
  if (!value) return null
  try {
    const parsed = new URL(value, import.meta.client ? window.location.origin : 'http://localhost')
    if (imageApiOrigin && parsed.pathname.startsWith('/api/files/')) {
      parsed.protocol = new URL(imageApiOrigin).protocol
      parsed.host = new URL(imageApiOrigin).host
    }
    return parsed.toString()
  } catch {
    return value
  }
}

function blankState(): BatchState {
  return { status: 'PENDING', gridUrl: null, gridFileId: null, error: '', selectedCell: null, cellBySlot: Array.from({ length: CELL_COUNT }, (_, index) => index), filledSlots: [] }
}

function stateOf(batch: BatchSlots): BatchState {
  if (!states[batch.key]) states[batch.key] = blankState()
  return states[batch.key]
}

function formatSeconds(value: number) { return `${Number(value || 0).toFixed(1)}s` }
function shotBrief(shot: ShotData) { return (shot.prompt || shot.visual_description || shot.action || '暂无画面描述').slice(0, 60) }
function batchShots(batch: BatchSlots) { return batch.slots.filter((slot): slot is ShotData => Boolean(slot)) }
function batchShotCount(batch: BatchSlots) { return batchShots(batch).length }
function batchDuration(batch: BatchSlots) { return batchShots(batch).reduce((total, shot) => total + Number(shot.duration_seconds || 0), 0) }
function batchRangeLabel(batch: BatchSlots) {
  const list = batchShots(batch)
  if (!list.length) return '暂无镜头'
  const min = list[0]!.sequence_no
  const max = list[list.length - 1]!.sequence_no
  return min === max ? `镜头 #${String(min).padStart(3, '0')}` : `镜头 #${String(min).padStart(3, '0')}-#${String(max).padStart(3, '0')}`
}
function batchSceneLabel(batch: BatchSlots) {
  for (const shot of batchShots(batch)) {
    const group = groups.value.find((item) => item.id === shot.shot_group_id)
    const label = group?.scene_label?.trim() || shot.location?.trim()
    if (label) return label
  }
  return ''
}
function batchStatusLabel(batch: BatchSlots) {
  const state = stateOf(batch)
  if (state.status === 'GENERATING') return '生成中...'
  if (state.status === 'GENERATED') {
    const expected = batchShotCount(batch)
    return state.filledSlots.length >= expected && expected > 0 ? '已填入' : '已生成'
  }
  return '未生成'
}
function batchStateClass(batch: BatchSlots) {
  const state = stateOf(batch)
  if (state.status === 'GENERATING') return 'text-[#e87517]'
  if (state.status === 'GENERATED') return 'text-[#278d68]'
  return ''
}
function toggleExpanded(key: string) { expanded.value[key] = !(expanded.value[key] ?? false) }
function isFilled(batch: BatchSlots) { const expected = batchShotCount(batch); return expected > 0 && stateOf(batch).filledSlots.length >= expected }
function isSlotFilled(batch: BatchSlots, slotIndex: number) { return stateOf(batch).filledSlots.includes(slotIndex) }
function cellOfSlot(batch: BatchSlots, slotIndex: number) { return stateOf(batch).cellBySlot[slotIndex] ?? slotIndex }

/** CSS 三倍背景裁切：以九宫格图为底图预览单格，无需服务端切图。 */
function cellStyle(gridUrl: string | null, cell: number): Record<string, string> {
  if (!gridUrl) return {}
  const row = Math.floor(cell / 3)
  const col = cell % 3
  return { backgroundImage: `url(${gridUrl})`, backgroundSize: '300% 300%', backgroundPosition: `${col * 50}% ${row * 50}%` }
}

function selectCell(batch: BatchSlots, cell: number) {
  const state = stateOf(batch)
  state.selectedCell = state.selectedCell === cell ? null : cell
}

function pickSlot(batch: BatchSlots, slotIndex: number) {
  const state = stateOf(batch)
  if (!batch.slots[slotIndex]) return
  if (state.selectedCell != null) {
    const cell = state.selectedCell
    state.cellBySlot.forEach((assigned, index) => { if (assigned === cell && index !== slotIndex) state.cellBySlot[index] = index })
    state.cellBySlot[slotIndex] = cell
    state.selectedCell = null
    message.success(`已把第 ${cell + 1} 格映射到镜头 #${String(batch.slots[slotIndex]!.sequence_no).padStart(3, '0')}`)
    return
  }
  state.selectedCell = cellOfSlot(batch, slotIndex)
}

function buildGridPrompt(batch: BatchSlots): string {
  const list = batchShots(batch)
  const cells = list.map((shot, index) => {
    const main = (shot.prompt || shot.visual_description || shot.action || '无画面描述').replace(/\s+/g, ' ').slice(0, 160)
    const camera = [shot.shot_size, shot.camera_movement].filter(Boolean).join('·')
    return `${index + 1}. ${camera ? `${main}（${camera}）` : main}`
  })
  return [
    `电影级连续分镜九宫格画板：严格 3×3 网格，共 9 格（不足 9 格的位置留白），从左到右、自上而下按顺序对应以下 ${list.length} 个连续镜头，每格绘制一个镜头画面。`,
    ...cells,
    '要求：所有格子保持同一套角色形象、服装道具、场景美术与光线色调；相邻格子之间动作与空间连续，像同一的一场戏；构图留出画面主体安全区；格子之间用细白色分隔线；画面中不出现文字、字幕或水印。',
    settingsForm.styleSupplement.trim() ? `风格补充：${settingsForm.styleSupplement.trim()}` : '',
  ].filter(Boolean).join('\n')
}

async function generateBatch(batch: BatchSlots) {
  const state = stateOf(batch)
  if (state.status === 'GENERATING') return
  if (!settingsForm.modelKey) {
    message.warning('请先在生成设置中选择图片模型')
    openSettings()
    return
  }
  if (!batchShotCount(batch)) { message.warning('这一批没有镜头'); return }
  const [providerConfigId, model] = settingsForm.modelKey.split('::')
  state.status = 'GENERATING'
  state.error = ''
  try {
    const response = await http.post<StoryboardTaskResponse>(`/projects/${projectId.value}/episodes/${episodeId.value}/storyboard-tasks?batch_no=${batch.index}&shot_ids=${batchShots(batch).map((shot) => shot.id).join('&shot_ids=')}&provider_config_id=${Number(providerConfigId)}&model=${encodeURIComponent(model)}&size=${settingsForm.size}&style_supplement=${encodeURIComponent(settingsForm.styleSupplement)}`, {})
    let detail = response
    for (let attempt = 0; attempt < 180 && !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(detail.status); attempt += 1) {
      await new Promise((resolve) => setTimeout(resolve, 1000))
      detail = await http.get<StoryboardTaskResponse>(`/projects/${projectId.value}/episodes/${episodeId.value}/storyboard-tasks/${response.task_id}`)
    }
    if (detail.status !== 'SUCCEEDED' || !detail.grid_file_id) throw new Error(detail.error_message || '九宫格生成失败')
    state.gridFileId = detail.grid_file_id
    state.gridUrl = resolveImageUrl(detail.signed_grid_url)
    state.status = 'GENERATED'
    state.selectedCell = null
    expanded.value[batch.key] = true
    message.success(`第 ${batch.index} 批九宫格已生成`)
  } catch (error) {
    state.status = state.gridUrl ? 'GENERATED' : 'PENDING'
    state.error = error instanceof Error ? error.message : '九宫格生成失败'
    message.error(state.error)
  }
}

async function generateAll() {
  if (generatingAll.value) return
  if (!settingsForm.modelKey) {
    message.warning('请先在生成设置中选择图片模型')
    openSettings()
    return
  }
  const pending = batches.value.filter((batch) => stateOf(batch).status !== 'GENERATED')
  if (!pending.length) { message.info('所有批次都已生成，如需重绘请逐批「重新生成」'); return }
  generatingAll.value = true
  let success = 0
  try {
    for (const batch of pending) {
      await generateBatch(batch)
      if (stateOf(batch).status === 'GENERATED') success += 1
    }
    message.success(`全部生成完成：${success}/${pending.length} 批成功`)
  } finally {
    generatingAll.value = false
  }
}

/** 保存格位映射。后端映射持久化接口（storyboard mappings）就绪后，这里应同步提交。 */
async function fillBatch(batch: BatchSlots) {
  const state = stateOf(batch)
  if (state.status !== 'GENERATED') { message.warning('请先生成九宫格'); return }
  try {
    const cells = batch.slots.map((slot, index) => slot ? ({ cell: cellOfSlot(batch, index), shot_id: slot.id }) : null).filter(Boolean)
    await http.put(`/projects/${projectId.value}/episodes/${episodeId.value}/storyboards/${batch.index}/cells`, { grid_file_id: state.gridFileId, cells })
    await http.post<{ imported: number }>(`/projects/${projectId.value}/episodes/${episodeId.value}/storyboards/${batch.index}/import`, { cells })
    state.filledSlots = batch.slots.map((slot, index) => (slot ? index : -1)).filter((index) => index >= 0)
    message.success(`第 ${batch.index} 批已填入 ${state.filledSlots.length}/${batchShotCount(batch)} 个镜头`)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '填入分镜失败')
  }
}

function downloadFile(name: string, content: string, type: string) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = name
  anchor.click()
  URL.revokeObjectURL(url)
}

function exportMapping() {
  if (!filledCount.value) { message.warning('还没有已填入的批次'); return }
  const payload = {
    episode_id: episodeParams.value?.episode_id ?? null,
    exported_at: new Date().toISOString(),
    batches: batches.value.filter((batch) => stateOf(batch).filledSlots.length).map((batch) => ({
      batch_no: batch.index,
      grid_file_id: stateOf(batch).gridFileId,
      cells: batch.slots.map((slot, slotIndex) => slot ? ({ cell: cellOfSlot(batch, slotIndex), shot_id: slot.id, sequence_no: slot.sequence_no }) : null).filter(Boolean),
    })),
  }
  downloadFile(`storyboard-mapping-${projectId.value}.json`, JSON.stringify(payload, null, 2), 'application/json')
  message.success('映射 JSON 已导出')
}

function openSettings() {
  settingsForm.modelKey = settingsForm.modelKey || imageModelOptions.value[0]?.value || ''
  settingsVisible.value = true
}
function saveSettings() {
  if (!settingsForm.modelKey) { message.warning('请选择图片模型'); return }
  settingsVisible.value = false
  message.success('生成设置已保存')
}

const workflowBase = computed(() => {
  const pid = String(route.params.projectId || activeProject.value?.id || '')
  const eid = String(route.params.episodeId || activeEpisodeId.value || '')
  return `/projects/${pid}${/^\d+$/.test(eid) ? `/episodes/${eid}` : ''}/workflow`
})
function goStep(stage: 'shots' | 'video') { navigateTo(`${workflowBase.value}/${stage}`) }

async function loadShots() {
  const id = projectId.value
  if (!Number.isInteger(id) || id <= 0) return
  loading.value = true
  try {
    const data = await http.get<ShotsResponse>(shotsPath.value)
    shots.value = data.list || []
    groups.value = data.groups || []
  } catch (error) {
    message.error(error instanceof Error ? error.message : '分镜加载失败')
  } finally {
    loading.value = false
  }
}

async function loadProviders() {
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    providers.value = response.list || []
  } catch {
    providers.value = []
  }
}

async function loadSavedStoryboards() {
  if (!episodeParams.value) return
  try {
    const response = await http.get<StoryboardListResponse>(`/projects/${projectId.value}/episodes/${episodeParams.value.episode_id}/storyboards`)
    for (const saved of response.list || []) {
      const batch = batches.value[saved.batch_no - 1]
      if (!batch) continue
      const state = stateOf(batch)
      state.gridFileId = saved.grid_file_id || null
      state.gridUrl = resolveImageUrl(saved.signed_grid_url)
      state.status = saved.status === 'IMPORTED' || saved.status === 'GENERATED' ? 'GENERATED' : state.status
      state.filledSlots = (saved.imported_shot_ids || []).map((id) => batch.slots.findIndex((slot) => slot?.id === id)).filter((index) => index >= 0)
      for (const cell of saved.cells || []) { const index = batch.slots.findIndex((slot) => slot?.id === cell.shot_id); if (index >= 0) state.cellBySlot[index] = cell.cell }
    }
  } catch { /* saved storyboard recovery is best effort */ }
}

async function reloadAll() { await Promise.all([loadShots(), loadProviders()]) }
async function initialise() {
  try { await loadProjects() } catch { /* keep cached project */ }
  try { await loadEpisodes(String(projectId.value || route.params.projectId || '')) } catch { /* keep local episode */ }
  await reloadAll()
  await loadSavedStoryboards()
}
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
.generate-button { display: inline-flex; align-items: center; gap: 6px; height: 34px; padding: 0 14px; border: none; border-radius: 8px; background: #e87517; font-size: 12px; font-weight: 500; color: #fff; transition: background-color 0.15s; }
.generate-button:hover { background: #d3660e; }
.generate-button:disabled { cursor: not-allowed; opacity: 0.55; }
.batch-header { display: flex; align-items: center; gap: 8px; width: 100%; padding: 10px 12px; cursor: pointer; text-align: left; transition: background-color 0.15s; }
.batch-header:hover { background: #f7f8fa; }
.grid-board { position: relative; }
.grid-cell { position: relative; border: none; background: transparent; cursor: pointer; outline: 1px dashed transparent; outline-offset: -4px; transition: outline-color 0.15s, background-color 0.15s; }
.grid-cell:hover { outline-color: rgba(232, 117, 23, 0.8); background: rgba(232, 117, 23, 0.08); }
.grid-cell--selected { outline: 2px solid #e87517; outline-offset: -4px; background: rgba(232, 117, 23, 0.12); }
.crop-row { display: flex; align-items: center; gap: 8px; padding: 3px; border-radius: 8px; cursor: pointer; transition: background-color 0.15s; }
.crop-row:hover { background: #f7f8fa; }
.crop-row--filled { background: #f5fcf8; }
.crop-row--filled:hover { background: #edf9f2; }
.crop-thumb { display: flex; height: 40px; width: 40px; flex-shrink: 0; align-items: center; justify-content: center; overflow: hidden; border: 1px solid #e1e4ec; border-radius: 6px; background: #fafbfd center / 300% 300% no-repeat; color: #c3c9d6; }
.fill-dot { display: block; height: 12px; width: 12px; flex-shrink: 0; border-radius: 999px; border: 1px solid #c9ced9; background: #fff; }
.fill-dot--picked { border-color: #e87517; box-shadow: 0 0 0 2px rgba(232, 117, 23, 0.18); }
.fill-dot--on { border-color: #278d68; background: #278d68; }
.chip { display: inline-flex; align-items: center; max-width: 100%; padding: 1px 7px; border-radius: 5px; font-size: 11px; line-height: 18px; white-space: nowrap; }
.chip-amber { background: #fff5e3; color: #b4791d; }
.form-label { display: block; margin-bottom: 6px; font-size: 12px; color: #687388; }
.ai-control { display: block; width: 100%; border: 1px solid #dfe2e8; border-radius: 8px; background: #fff; padding: 8px 10px; font-size: 12px; line-height: 1.5; color: #4b566b; outline: none; transition: border-color 0.15s, box-shadow 0.15s; }
.ai-control:focus { border-color: #9b91f5; box-shadow: 0 0 0 2px #eeecff; }
.ai-control:disabled { cursor: not-allowed; background: #f6f7f9; }
.ai-textarea { resize: vertical; }
</style>
