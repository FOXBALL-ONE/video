<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-5 text-[#20283b] sm:px-7">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col justify-between gap-5 sm:flex-row sm:items-end">
        <div>
          <p class="m-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee]">04 / 07 · PRODUCTION NODE</p>
          <h1 class="mt-4 text-[30px] font-medium leading-none">分镜管理</h1>
          <p class="mt-3 text-xs text-[#5d52ec]">{{ activeEpisodeLabel }}</p>
          <p class="mt-2 text-[13px] text-[#8a94aa]">把每一集拆成可执行的镜头，记录画面、调度、声音和生成提示词。</p>
        </div>
        <div class="flex w-full gap-2 sm:w-auto">
          <n-button :loading="loading" class="!h-10 !rounded-[10px] !border-[#dfe3eb] !px-3 !text-[#687388]" secondary @click="loadShots">↻ 刷新</n-button>
          <n-button class="!h-10 !rounded-[10px] !bg-[#6654ee] !px-4 !text-white" type="primary" @click="openCreate">＋ 新增镜头</n-button>
        </div>
      </header>

      <ProductionSteps active="shots" />

      <section class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        <section class="overflow-hidden rounded-[14px] border border-[#dfe3eb] bg-white">
          <div class="flex flex-col gap-4 border-b border-[#e8eaf0] px-5 py-5 sm:flex-row sm:items-center sm:justify-between">
            <div>
              <div class="flex items-center gap-3">
                <h2 class="m-0 text-base font-semibold text-[#273148]">{{ activeEpisode?.title || '本集分镜' }}</h2>
                <n-tag size="small" :bordered="false" class="!bg-[#f0eeff] !text-[#5c50dc]">{{ shots.length }} 个镜头</n-tag>
              </div>
              <p class="mt-1.5 text-xs text-[#929bad]">总时长 {{ formatDuration(totalDuration) }} · {{ sceneCount }} 个场景 · 最近保存 {{ lastSavedLabel }}</p>
            </div>
            <div class="flex items-center gap-2 text-xs text-[#8994a8]">
              <span class="h-2 w-2 rounded-full" :class="continuityIssues.length ? 'bg-[#e5a239]' : 'bg-[#38ad7b]'"></span>
              {{ continuityIssues.length ? `${continuityIssues.length} 项连续性提醒` : '连续性检查通过' }}
            </div>
          </div>

          <div v-if="loading" class="space-y-3 px-5 py-5">
            <div v-for="item in 3" :key="item" class="h-32 animate-pulse rounded-[10px] bg-[#f2f3f7]"></div>
          </div>
          <div v-else-if="!shots.length" class="flex min-h-[380px] flex-col items-center justify-center px-6 text-center">
            <span class="flex h-14 w-14 items-center justify-center rounded-[14px] bg-[#f0eeff] text-2xl text-[#6654ee]">▦</span>
            <h3 class="mt-4 text-sm font-semibold text-[#4d5870]">这一集还没有分镜</h3>
            <p class="mt-2 max-w-[280px] text-xs leading-5 text-[#929bad]">从第一个镜头开始，把剧本场次变成可拍摄、可生成的画面。</p>
            <n-button class="mt-4 !rounded-[9px] !bg-[#6654ee] !text-white" type="primary" @click="openCreate">＋ 新增镜头</n-button>
          </div>
          <div v-else class="space-y-3 px-5 py-5">
            <article v-for="shot in shots" :key="shot.id" class="group rounded-[10px] border border-[#e4e7ee] bg-white p-4 transition-shadow hover:shadow-[0_8px_24px_rgba(46,51,76,0.08)]">
              <div class="flex items-start gap-3">
                <div class="flex h-14 w-14 shrink-0 flex-col items-center justify-center rounded-[9px] bg-[#f3f2ff] text-[#6654ee]"><span class="text-[10px] font-semibold">SHOT</span><strong class="mt-0.5 text-lg leading-none">{{ String(shot.sequence_no).padStart(2, '0') }}</strong></div>
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-1.5">
                    <h3 class="m-0 min-w-0 max-w-full truncate text-sm font-semibold text-[#313a4e]">{{ shot.title }}</h3>
                    <n-tag v-if="shot.shot_size" size="tiny" :bordered="false" class="!bg-[#edf4ff] !text-[#3976bd]">{{ shot.shot_size }}</n-tag>
                    <n-tag v-if="shot.focal_length" size="tiny" :bordered="false" class="!bg-[#fff5e8] !text-[#bb7620]">{{ shot.focal_length }}</n-tag>
                    <n-tag size="tiny" :bordered="false" :type="statusTone(shot.status)">{{ statusLabel[shot.status] || shot.status }}</n-tag>
                    <span class="ml-auto shrink-0 text-[11px] text-[#929bad]">{{ formatSeconds(shot.duration_seconds) }}</span>
                  </div>
                  <p class="mt-1.5 truncate text-[11px] text-[#8994a8]">{{ shotLocation(shot) || '未设置场景信息' }}<span v-if="shot.camera_angle"> · {{ shot.camera_angle }}</span><span v-if="shot.camera_movement"> · {{ shot.camera_movement }}</span></p>
                </div>
                <div class="flex shrink-0 items-center gap-1 opacity-70 transition-opacity group-hover:opacity-100">
                  <button type="button" class="icon-button" title="编辑镜头" aria-label="编辑镜头" @click="openEdit(shot)">✎</button>
                  <button type="button" class="icon-button icon-button-danger" title="删除镜头" aria-label="删除镜头" @click="removeShot(shot)">⌫</button>
                </div>
              </div>
              <div class="mt-3 grid gap-3 border-t border-[#f0f1f5] pt-3 sm:grid-cols-[minmax(0,1fr)_180px]">
                <div class="min-w-0">
                  <p v-if="shot.visual_description || shot.action" class="m-0 line-clamp-3 text-[13px] leading-5 text-[#4f596d]">{{ shot.visual_description || shot.action }}</p>
                  <p v-if="shot.dialogue" class="mt-2 line-clamp-2 text-xs leading-5 text-[#168275]"><span class="mr-1 rounded bg-[#e5f6f2] px-1.5 py-0.5 text-[10px]">对白</span>{{ shot.dialogue }}</p>
                  <p v-if="shot.composition" class="mt-2 line-clamp-2 text-[11px] leading-4 text-[#929bad]">构图：{{ shot.composition }}</p>
                </div>
                <div class="space-y-2 text-[11px] text-[#8994a8] sm:border-l sm:border-[#f0f1f5] sm:pl-3">
                  <div v-if="shot.characters" class="flex gap-1.5"><span class="shrink-0 text-[#b0b6c2]">角色</span><span class="line-clamp-2 text-[#687388]">{{ displayList(shot.characters) }}</span></div>
                  <div v-if="shot.props" class="flex gap-1.5"><span class="shrink-0 text-[#b0b6c2]">道具</span><span class="line-clamp-2 text-[#687388]">{{ displayList(shot.props) }}</span></div>
                  <div v-if="shot.atmosphere" class="flex gap-1.5"><span class="shrink-0 text-[#b0b6c2]">氛围</span><span class="line-clamp-2 text-[#687388]">{{ shot.atmosphere }}</span></div>
                </div>
              </div>
              <p v-if="shot.prompt" class="mt-3 line-clamp-2 rounded-md bg-[#f8f8fb] px-2.5 py-2 text-[11px] leading-4 text-[#737c8d]">提示词：{{ shot.prompt }}</p>
            </article>
          </div>

          <div class="flex flex-col gap-3 border-t border-[#e8eaf0] bg-[#fbfbfd] px-5 py-4 sm:flex-row sm:items-center">
            <div class="min-w-0 flex-1"><strong class="text-xs font-semibold text-[#4d5870]">连续性检查</strong><p v-if="continuityIssues.length" class="mt-1 truncate text-[11px] text-[#9a742c]">{{ continuityIssues[0].description }}</p><p v-else class="mt-1 text-[11px] text-[#38a778]">当前集镜头顺序、景别和动作衔接没有待处理问题。</p></div>
            <n-button :disabled="!continuityIssues.length" :loading="resolving" class="!rounded-[8px] !border-[#f0d6a6] !text-[#a87320]" secondary size="small" @click="resolveIssues">一键修复</n-button>
          </div>
        </section>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="分镜管理助手" @send="updateAssistant" />
      </section>
    </div>

    <n-drawer v-model:show="drawerVisible" :width="drawerWidth" placement="right">
      <n-drawer-content :title="editingShotId == null ? '新增镜头' : `编辑镜头 #${String(editingSequence).padStart(2, '0')}`" closable>
        <n-form label-placement="top" class="shot-form" @submit.prevent="saveShot">
          <n-form-item label="镜头标题" required><n-input v-model:value="form.title" placeholder="例如：雨中收到旧信" maxlength="200" show-count /></n-form-item>
          <div class="grid gap-3 sm:grid-cols-3"><n-form-item label="场景"><n-input v-model:value="form.location" placeholder="地点" /></n-form-item><n-form-item label="时间"><n-input v-model:value="form.time_of_day" placeholder="夜 / 清晨" /></n-form-item><n-form-item label="内外景"><n-select v-model:value="form.interior_exterior" clearable :options="interiorOptions" placeholder="选择" /></n-form-item></div>
          <div class="grid gap-3 sm:grid-cols-3"><n-form-item label="景别"><n-select v-model:value="form.shot_size" clearable :options="shotSizeOptions" placeholder="选择景别" /></n-form-item><n-form-item label="焦段"><n-input v-model:value="form.focal_length" placeholder="50mm" /></n-form-item><n-form-item label="时长（秒）"><n-input-number v-model:value="form.duration_seconds" :min="0" :step="0.1" class="w-full" /></n-form-item></div>
          <div class="grid gap-3 sm:grid-cols-2"><n-form-item label="机位 / 轴线"><n-input v-model:value="form.camera_angle" placeholder="例如：沿 A2 轴左侧的斜向机位" /></n-form-item><n-form-item label="运镜"><n-input v-model:value="form.camera_movement" placeholder="固定、推近、横移或其他调度" /></n-form-item></div>
          <n-form-item label="画面描述"><n-input v-model:value="form.visual_description" type="textarea" :rows="3" placeholder="描述画面中人物、动作和前中后景关系" /></n-form-item>
          <n-form-item label="构图与站位"><n-input v-model:value="form.composition" type="textarea" :rows="2" placeholder="例如：人物位于右前景，门框形成暗框" /></n-form-item>
          <n-form-item label="人物动作"><n-input v-model:value="form.action" type="textarea" :rows="3" placeholder="记录动作节拍和表演要求" /></n-form-item>
          <n-form-item label="对白 / 旁白"><n-input v-model:value="form.dialogue" type="textarea" :rows="2" placeholder="没有对白可以留空" /></n-form-item>
          <div class="grid gap-3 sm:grid-cols-2"><n-form-item label="角色"><n-input v-model:value="form.characters" placeholder="多个角色用逗号分隔" /></n-form-item><n-form-item label="道具 / 资产"><n-input v-model:value="form.props" placeholder="多个道具用逗号分隔" /></n-form-item></div>
          <div class="grid gap-3 sm:grid-cols-2"><n-form-item label="光线与情绪"><n-input v-model:value="form.atmosphere" placeholder="冷暗、压迫、温暖..." /></n-form-item><n-form-item label="叙事目的"><n-input v-model:value="form.narrative_purpose" placeholder="本镜头要传递的信息" /></n-form-item></div>
          <n-form-item label="生成提示词"><n-input v-model:value="form.prompt" type="textarea" :rows="3" placeholder="给画面生成模型的正向提示词" /></n-form-item>
          <n-form-item label="反向提示词"><n-input v-model:value="form.negative_prompt" type="textarea" :rows="2" placeholder="需要避免的内容" /></n-form-item>
          <n-form-item label="状态"><n-select v-model:value="form.status" :options="statusOptions" /></n-form-item>
          <div class="flex justify-end gap-2 border-t border-[#e8eaf0] pt-4"><n-button class="!rounded-[8px]" @click="drawerVisible = false">取消</n-button><n-button :loading="saving" class="!rounded-[8px] !bg-[#6654ee] !text-white" type="primary" attr-type="submit">保存分镜</n-button></div>
        </n-form>
      </n-drawer-content>
    </n-drawer>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'

interface ShotData { id: number; episode_id?: number | null; sequence_no: number; title: string; location?: string | null; time_of_day?: string | null; interior_exterior?: string | null; shot_size?: string | null; camera_movement?: string | null; camera_angle?: string | null; focal_length?: string | null; composition?: string | null; visual_description?: string | null; action?: string | null; dialogue?: string | null; characters?: string | null; props?: string | null; prompt?: string | null; negative_prompt?: string | null; atmosphere?: string | null; narrative_purpose?: string | null; duration_seconds: number; status: string; updated_at?: string | null }
interface ContinuityIssue { id: number; description: string }
interface ShotsResponse { list: ShotData[]; continuity_issues: ContinuityIssue[] }
interface ShotForm { title: string; location: string; time_of_day: string; interior_exterior: string | null; shot_size: string | null; camera_movement: string; camera_angle: string; focal_length: string; composition: string; visual_description: string; action: string; dialogue: string; characters: string; props: string; prompt: string; negative_prompt: string; atmosphere: string; narrative_purpose: string; duration_seconds: number; status: string }

const route = useRoute()
const message = useMessage()
const http = useHttp()
const { activeProject, loadProjects } = useProjectWorkspace()
const { activeEpisodeId, activeEpisode, loadEpisodes } = useEpisodeWorkspace()
const shots = ref<ShotData[]>([])
const continuityIssues = ref<ContinuityIssue[]>([])
const loading = ref(false)
const saving = ref(false)
const resolving = ref(false)
const drawerVisible = ref(false)
const editingShotId = ref<number | null>(null)
const editingSequence = ref(0)
const lastSavedAt = ref<string | null>(null)
const assistantMessage = ref('我是分镜导演助手。可以检查景别变化、轴线、动作连续性，并把每个镜头整理为视频模型提示词。')
const assistantSuggestions = ['优化镜头节奏', '检查轴线连续性', '补全运镜提示词']
const blankForm = (): ShotForm => ({ title: '', location: '', time_of_day: '', interior_exterior: null, shot_size: null, camera_movement: '', camera_angle: '', focal_length: '', composition: '', visual_description: '', action: '', dialogue: '', characters: '', props: '', prompt: '', negative_prompt: '', atmosphere: '', narrative_purpose: '', duration_seconds: 3, status: 'DRAFT' })
const form = ref<ShotForm>(blankForm())
const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id))
const episodeId = computed(() => String(route.params.episodeId || activeEpisodeId.value || ''))
const episodeParams = computed(() => { const value = Number(episodeId.value); return Number.isInteger(value) && value > 0 ? { episode_id: value } : undefined })
const shotsPath = computed(() => episodeParams.value ? `/projects/${projectId.value}/episodes/${episodeParams.value.episode_id}/shots` : `/projects/${projectId.value}/shots`)
const activeEpisodeLabel = computed(() => activeEpisode.value ? `第${String(activeEpisode.value.episodeNo).padStart(3, '0')}集 · ${activeEpisode.value.title}` : '请选择一个剧集')
const totalDuration = computed(() => shots.value.reduce((total, shot) => total + Number(shot.duration_seconds || 0), 0))
const sceneCount = computed(() => new Set(shots.value.map((shot) => shot.location?.trim()).filter(Boolean)).size)
const lastSavedLabel = computed(() => lastSavedAt.value ? new Date(lastSavedAt.value).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '尚未保存')
const drawerWidth = computed(() => 'min(640px, 100vw)')
const statusLabel: Record<string, string> = { DRAFT: '草稿', READY: '已完成', NEEDS_ATTENTION: '待优化', GENERATING: '生成中', GENERATED: '已生成', APPROVED: '已通过' }
const statusOptions = Object.entries(statusLabel).map(([value, label]) => ({ value, label }))
const interiorOptions = [{ value: '内景', label: '内景' }, { value: '外景', label: '外景' }]
const shotSizeOptions = ['大远景', '远景', '全景', '中景', '近景', '特写', '大特写'].map((value) => ({ value, label: value }))
function formatSeconds(value: number) { return `${Number(value || 0).toFixed(1)}s` }
function formatDuration(value: number) { const seconds = Math.max(0, Math.round(value || 0)); return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}` }
function shotLocation(shot: ShotData) { return [shot.location, shot.time_of_day, shot.interior_exterior].filter(Boolean).join(' · ') }
function displayList(value: string) { return value.split(/[，,]/).map((item) => item.trim()).filter(Boolean).join('、') }
function statusTone(status: string) { return ['READY', 'GENERATED', 'APPROVED'].includes(status) ? 'success' : status === 'NEEDS_ATTENTION' ? 'warning' : 'default' }
function cleanText(value: string | null | undefined) { return value?.trim() || null }
function toForm(shot: ShotData): ShotForm { return { title: shot.title, location: shot.location || '', time_of_day: shot.time_of_day || '', interior_exterior: shot.interior_exterior || null, shot_size: shot.shot_size || null, camera_movement: shot.camera_movement || '', camera_angle: shot.camera_angle || '', focal_length: shot.focal_length || '', composition: shot.composition || '', visual_description: shot.visual_description || '', action: shot.action || '', dialogue: shot.dialogue || '', characters: shot.characters || '', props: shot.props || '', prompt: shot.prompt || '', negative_prompt: shot.negative_prompt || '', atmosphere: shot.atmosphere || '', narrative_purpose: shot.narrative_purpose || '', duration_seconds: Number(shot.duration_seconds || 0), status: shot.status } }
function applyResponse(data: ShotsResponse) { shots.value = data.list || []; continuityIssues.value = data.continuity_issues || []; lastSavedAt.value = shots.value.map((shot) => shot.updated_at).filter(Boolean).sort().at(-1) || null }
async function loadShots() { const id = projectId.value; if (!Number.isInteger(id) || id <= 0) return; loading.value = true; try { applyResponse(await http.get<ShotsResponse>(shotsPath.value)) } catch (error) { message.error(error instanceof Error ? error.message : '分镜加载失败') } finally { loading.value = false } }
function openCreate() { editingShotId.value = null; editingSequence.value = shots.value.length + 1; form.value = blankForm(); drawerVisible.value = true }
function openEdit(shot: ShotData) { editingShotId.value = shot.id; editingSequence.value = shot.sequence_no; form.value = toForm(shot); drawerVisible.value = true }
async function saveShot() { const id = projectId.value; if (!Number.isInteger(id) || id <= 0 || !form.value.title.trim()) { message.warning('请先填写镜头标题'); return }; saving.value = true; const payload = { title: form.value.title.trim(), location: cleanText(form.value.location), time_of_day: cleanText(form.value.time_of_day), interior_exterior: cleanText(form.value.interior_exterior), shot_size: cleanText(form.value.shot_size), camera_movement: cleanText(form.value.camera_movement), camera_angle: cleanText(form.value.camera_angle), focal_length: cleanText(form.value.focal_length), composition: cleanText(form.value.composition), visual_description: cleanText(form.value.visual_description), action: cleanText(form.value.action), dialogue: cleanText(form.value.dialogue), characters: cleanText(form.value.characters), props: cleanText(form.value.props), prompt: cleanText(form.value.prompt), negative_prompt: cleanText(form.value.negative_prompt), atmosphere: cleanText(form.value.atmosphere), narrative_purpose: cleanText(form.value.narrative_purpose), duration_seconds: Number(form.value.duration_seconds) || 0, status: form.value.status }; try { if (editingShotId.value == null) await http.post(shotsPath.value, payload); else await http.put(`${shotsPath.value}/${editingShotId.value}`, payload); const wasNew = editingShotId.value == null; drawerVisible.value = false; await loadShots(); try { await loadEpisodes(String(id)) } catch { /* keep local navigation state */ }; message.success(wasNew ? '分镜已新增' : '分镜已保存') } catch (error) { message.error(error instanceof Error ? error.message : '分镜保存失败') } finally { saving.value = false } }
async function removeShot(shot: ShotData) { if (import.meta.client && !window.confirm(`确定删除镜头 #${String(shot.sequence_no).padStart(2, '0')}「${shot.title}」吗？`)) return; const id = projectId.value; if (!Number.isInteger(id) || id <= 0) return; try { await http.delete(`${shotsPath.value}/${shot.id}`); await loadShots(); message.success('镜头已删除') } catch (error) { message.error(error instanceof Error ? error.message : '镜头删除失败') } }
async function resolveIssues() { const id = projectId.value; if (!Number.isInteger(id) || id <= 0) return; resolving.value = true; try { await http.post(`${shotsPath.value}/continuity/resolve`); await loadShots(); message.success('连续性问题已修复') } catch (error) { message.error(error instanceof Error ? error.message : '连续性问题处理失败') } finally { resolving.value = false } }
function updateAssistant(value: string) { assistantMessage.value = `收到“${value}”，我会结合当前分镜给出调度建议。` }
async function initialise() { try { await loadProjects() } catch { /* keep cached project */ }; try { await loadEpisodes(String(projectId.value || route.params.projectId || '')) } catch { /* keep local episode */ }; await loadShots() }
onMounted(initialise)
watch(() => activeProject.value?.id, (next, previous) => { if (next && next !== previous) loadShots() })
watch(episodeId, (next, previous) => { if (next !== previous) loadShots() })
</script>

<style scoped>
.icon-button { display: inline-flex; height: 28px; width: 28px; align-items: center; justify-content: center; border-radius: 7px; color: #687388; font-size: 16px; transition: background-color 0.15s, color 0.15s; }
.icon-button:hover { background: #f0eeff; color: #5c50dc; }
.icon-button-danger:hover { background: #fff0f0; color: #cf4d58; }
.shot-form :deep(.n-form-item) { margin-bottom: 13px; }
.shot-form :deep(.n-form-item-label) { color: #687388; font-size: 12px; }
</style>
