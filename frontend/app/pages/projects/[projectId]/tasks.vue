<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-6 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <p class="m-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee]">TASK CENTER</p>
          <h1 class="mt-3 text-[30px] font-medium tracking-normal">任务中心</h1>
          <p class="mt-2 text-xs text-[#8994a8]">{{ activeProject?.name || '当前项目' }} · {{ activeCount }} 个活动任务</p>
        </div>
        <div class="flex items-center gap-2">
          <n-button :loading="loading" secondary class="!h-10 !rounded-[9px]" @click="loadTasks">
            <template #icon><span aria-hidden="true">↻</span></template>
            刷新
          </n-button>
        </div>
      </header>

      <section class="mt-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div v-for="item in summary" :key="item.key" class="rounded-[11px] border border-[#e1e4eb] bg-white px-4 py-3">
          <span class="text-[11px] text-[#8994a8]">{{ item.label }}</span>
          <strong class="mt-1.5 block text-xl font-semibold" :class="item.color">{{ item.value }}</strong>
        </div>
      </section>

      <section class="mt-5 rounded-[14px] border border-[#dfe3eb] bg-white">
        <div class="flex flex-col gap-3 border-b border-[#e8eaf0] px-4 py-4 sm:flex-row sm:items-center sm:justify-between sm:px-5">
          <div class="flex flex-wrap items-center gap-2">
            <n-select v-model:value="filters.status" clearable size="small" class="!w-[145px]" placeholder="全部状态" :options="statusOptions" @update:value="resetAndLoad" />
            <n-select v-model:value="filters.taskType" clearable size="small" class="!w-[160px]" placeholder="全部类型" :options="typeOptions" @update:value="resetAndLoad" />
            <n-select v-model:value="filters.episodeId" clearable size="small" class="!w-[145px]" placeholder="全部剧集" :options="episodeOptions" @update:value="resetAndLoad" />
          </div>
          <n-checkbox v-model:checked="filters.activeOnly" @update:checked="resetAndLoad">只看活动任务</n-checkbox>
        </div>

        <div v-if="loading && !tasks.length" class="flex min-h-[330px] items-center justify-center text-sm text-[#8994a8]">正在加载任务...</div>
        <div v-else-if="errorMessage && !tasks.length" class="flex min-h-[330px] flex-col items-center justify-center gap-3 px-5 text-center">
          <p class="m-0 text-sm text-[#b45d64]">{{ errorMessage }}</p>
          <n-button size="small" secondary @click="loadTasks">重试</n-button>
        </div>
        <div v-else-if="!tasks.length" class="flex min-h-[330px] flex-col items-center justify-center gap-2 px-5 text-center">
          <span class="flex h-11 w-11 items-center justify-center rounded-full bg-[#f0eeff] text-xl text-[#6654ee]" aria-hidden="true">✓</span>
          <strong class="text-sm font-medium text-[#4c5870]">暂无任务</strong>
          <span class="text-xs text-[#9aa3b3]">当前筛选条件下没有可显示的任务</span>
        </div>
        <div v-else class="overflow-x-auto">
          <table class="w-full min-w-[850px] border-collapse text-left text-xs">
            <thead class="bg-[#fafafd] text-[11px] text-[#8994a8]">
              <tr>
                <th class="px-5 py-3 font-medium">任务</th>
                <th class="px-3 py-3 font-medium">范围</th>
                <th class="px-3 py-3 font-medium">状态</th>
                <th class="px-3 py-3 font-medium">进度</th>
                <th class="px-3 py-3 font-medium">模型</th>
                <th class="px-3 py-3 font-medium">创建时间</th>
                <th class="px-5 py-3 text-right font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="task in tasks" :key="task.id" class="border-t border-[#edf0f4] align-middle transition-colors hover:bg-[#fcfcfe]">
                <td class="max-w-[245px] px-5 py-4">
                  <button type="button" class="flex min-w-0 items-center gap-2 text-left" @click="openDetail(task)">
                    <span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-[8px] bg-[#f0eeff] text-sm text-[#6654ee]" aria-hidden="true">{{ taskIcon(task.task_type) }}</span>
                    <span class="min-w-0"><strong class="block truncate font-medium text-[#344058]">{{ task.title }}</strong><small class="mt-1 block text-[10px] text-[#9aa3b3]">#{{ task.id }} · {{ typeLabel(task.task_type) }}</small></span>
                  </button>
                </td>
                <td class="px-3 py-4 text-[#69758b]">{{ task.episode_id ? `第 ${task.episode_id} 集` : '项目级' }}</td>
                <td class="px-3 py-4"><n-tag :bordered="false" size="small" :type="statusType(task.status)">{{ statusLabel(task.status) }}</n-tag></td>
                <td class="w-[150px] px-3 py-4">
                  <div class="flex items-center gap-2"><n-progress :percentage="task.progress" :show-indicator="false" :status="progressStatus(task.status)" class="min-w-[75px]" /><span class="w-9 text-right text-[10px] text-[#7c879b]">{{ task.progress_indeterminate ? '处理中' : `${task.progress}%` }}</span></div>
                  <small v-if="task.stage" class="mt-1 block truncate text-[10px] text-[#a0a8b6]">{{ stageLabel(task.stage) }}</small>
                </td>
                <td class="max-w-[130px] truncate px-3 py-4 text-[#69758b]">{{ task.model_name || '--' }}</td>
                <td class="whitespace-nowrap px-3 py-4 text-[#8994a8]">{{ formatDate(task.created_at) }}</td>
                <td class="px-5 py-4 text-right"><div class="flex justify-end gap-1.5"><n-button size="small" quaternary @click="openDetail(task)">详情</n-button><n-button v-if="task.task_type === 'SCRIPT_ANALYSIS' && task.status === 'FAILED'" size="small" quaternary type="warning" :loading="retryingTaskId === task.id" @click="retryTask(task)">重试</n-button><n-button v-if="task.can_cancel" size="small" quaternary type="error" @click="cancelTask(task)">取消</n-button></div></td>
              </tr>
            </tbody>
          </table>
        </div>

        <footer v-if="total > pageSize || tasks.length" class="flex items-center justify-between border-t border-[#e8eaf0] px-4 py-3 sm:px-5">
          <span class="text-[11px] text-[#9aa3b3]">共 {{ total }} 项 · 第 {{ page + 1 }} / {{ pageCount }} 页</span>
          <div class="flex gap-1.5"><n-button size="small" secondary :disabled="page <= 0 || loading" aria-label="上一页" @click="changePage(page - 1)">‹</n-button><n-button size="small" secondary :disabled="page + 1 >= pageCount || loading" aria-label="下一页" @click="changePage(page + 1)">›</n-button></div>
        </footer>
      </section>
    </div>

    <n-drawer v-model:show="detailVisible" :width="drawerWidth" placement="right">
      <n-drawer-content v-if="selectedTask" :title="selectedTask.title" closable>
        <div class="space-y-5">
          <div class="flex items-center justify-between"><n-tag :bordered="false" :type="statusType(selectedTask.status)">{{ statusLabel(selectedTask.status) }}</n-tag><span class="text-xs text-[#8994a8]">#{{ selectedTask.id }}</span></div>
          <div class="rounded-[10px] bg-[#fafafd] p-3"><div class="flex items-center justify-between text-xs"><span class="text-[#69758b]">执行进度</span><strong class="text-[#4e59c9]">{{ selectedTask.progress_indeterminate ? '处理中' : `${selectedTask.progress}%` }}</strong></div><n-progress class="mt-3" :percentage="selectedTask.progress" :show-indicator="false" :status="progressStatus(selectedTask.status)" /><p v-if="selectedTask.stage" class="mt-2 text-[11px] text-[#8b95a8]">{{ stageLabel(selectedTask.stage) }}</p></div>
          <div v-if="selectedTask.analysis_progress" class="rounded-[10px] border border-[#e2e6f5] bg-[#fbfcff] p-3">
            <div class="flex items-center justify-between gap-3"><span class="text-xs font-medium text-[#4c5870]">剧本解析进度</span><span class="text-[10px] text-[#8994a8]">{{ selectedTask.analysis_progress.stream ? '流式' : '非流式' }}</span></div>
            <div class="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-[#788399]"><span>已完成 {{ selectedTask.analysis_progress.completed }}/{{ selectedTask.analysis_progress.total }}</span><span>解析中 {{ selectedTask.analysis_progress.running }}</span><span>待处理 {{ selectedTask.analysis_progress.pending }}</span><span>失败 {{ selectedTask.analysis_progress.failed }}</span></div>
            <div v-if="selectedTask.analysis_progress.current_type" class="mt-2 text-[11px] text-[#4f62c7]">当前：{{ analysisTypeLabel(selectedTask.analysis_progress.current_type) }}</div>
            <div class="mt-3 space-y-2"><div v-for="item in selectedTask.analysis_progress.types" :key="item.type" class="rounded-[7px] border border-[#edf0f4] bg-white px-2.5 py-2"><div class="flex items-center justify-between gap-2"><span class="text-[11px] font-medium text-[#58647a]">{{ analysisTypeLabel(item.type) }}</span><n-tag size="tiny" :bordered="false" :type="analysisStatusType(item.status)">{{ analysisStatusLabel(item.status) }}</n-tag></div><div class="mt-1 flex items-center justify-between gap-2 text-[10px] text-[#9aa3b3]"><span>{{ item.model || '--' }} · {{ item.provider || '--' }}</span><span>{{ item.count }} 条结果</span></div><p v-if="item.error" class="mt-1 mb-0 text-[10px] leading-4 text-[#b45d64]">{{ item.error }}</p></div></div>
          </div>
          <dl class="grid grid-cols-2 gap-x-4 gap-y-3 text-xs"><div><dt class="text-[#9aa3b3]">任务类型</dt><dd class="mt-1 text-[#4c5870]">{{ typeLabel(selectedTask.task_type) }}</dd></div><div><dt class="text-[#9aa3b3]">执行范围</dt><dd class="mt-1 text-[#4c5870]">{{ selectedTask.episode_id ? `第 ${selectedTask.episode_id} 集` : '项目级' }}</dd></div><div><dt class="text-[#9aa3b3]">模型</dt><dd class="mt-1 truncate text-[#4c5870]">{{ selectedTask.model_name || '--' }}</dd></div><div><dt class="text-[#9aa3b3]">尝试次数</dt><dd class="mt-1 text-[#4c5870]">{{ selectedTask.attempt }} / {{ selectedTask.max_attempts }}</dd></div><div><dt class="text-[#9aa3b3]">创建时间</dt><dd class="mt-1 text-[#4c5870]">{{ formatDate(selectedTask.created_at) }}</dd></div><div><dt class="text-[#9aa3b3]">开始时间</dt><dd class="mt-1 text-[#4c5870]">{{ formatDate(selectedTask.started_at) }}</dd></div></dl>
          <div v-if="selectedTask.error_message" class="rounded-[10px] border border-[#f2dddd] bg-[#fffafa] p-3"><span class="text-[11px] text-[#b45d64]">{{ selectedTask.error_code || '任务错误' }}</span><p class="mt-1.5 m-0 text-xs leading-5 text-[#8c5d63]">{{ selectedTask.error_message }}</p></div>
          <div><h3 class="m-0 text-xs font-semibold text-[#4c5870]">执行时间线</h3><ol v-if="selectedTask.events?.length" class="mt-3 space-y-3 border-l border-[#e3e6ee] pl-4"><li v-for="event in selectedTask.events || []" :key="event.id" class="relative"><span class="absolute -left-[21px] top-0.5 h-2.5 w-2.5 rounded-full bg-[#7166e9] ring-4 ring-white" aria-hidden="true"></span><div class="flex items-center justify-between gap-2"><strong class="text-xs font-medium text-[#58647a]">{{ eventLabel(event.event_type) }}</strong><time class="text-[10px] text-[#9aa3b3]">{{ formatDate(event.created_at) }}</time></div><p v-if="event.message_code" class="mt-1 m-0 text-[11px] text-[#8b95a8]">{{ event.message_code }}</p></li></ol><p v-else class="mt-3 text-xs text-[#9aa3b3]">暂无事件记录</p></div>
          <n-button v-if="selectedTask.task_type === 'SCRIPT_ANALYSIS' && selectedTask.status === 'FAILED'" type="warning" secondary block :loading="retryingTaskId === selectedTask.id" @click="retryTask(selectedTask)">发起重试</n-button>
          <n-button v-if="selectedTask.can_cancel" type="error" secondary block @click="cancelTask(selectedTask)">取消任务</n-button>
        </div>
      </n-drawer-content>
    </n-drawer>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useProjectWorkspace } from '~/composables/useProjectWorkspace'
import { useEpisodeWorkspace } from '~/composables/useEpisodeWorkspace'
import type { TaskItem, TaskPage } from '~/types/tasks'

const route = useRoute()
const http = useHttp()
const message = useMessage()
const { activeProject } = useProjectWorkspace()
const { episodes, loadEpisodes } = useEpisodeWorkspace()
const projectId = computed(() => String(route.params.projectId || activeProject.value?.id || ''))
const tasks = ref<TaskItem[]>([])
const selectedTask = ref<TaskItem | null>(null)
const detailVisible = ref(false)
const loading = ref(false)
const errorMessage = ref('')
const retryingTaskId = ref<number | null>(null)
const drawerWidth = ref(470)
const total = ref(0)
const page = ref(0)
const pageSize = ref(20)
const counts = ref<Record<string, number>>({})
const filters = reactive<{ status: string | null; taskType: string | null; episodeId: number | null; activeOnly: boolean }>({ status: null, taskType: null, episodeId: null, activeOnly: false })
let pollTimer: ReturnType<typeof setInterval> | null = null
let requestSequence = 0

const statusOptions = [{ label: '等待中', value: 'PENDING' }, { label: '排队中', value: 'QUEUED' }, { label: '制作中', value: 'RUNNING' }, { label: '取消中', value: 'CANCEL_REQUESTED' }, { label: '已完成', value: 'SUCCEEDED' }, { label: '失败', value: 'FAILED' }, { label: '已取消', value: 'CANCELLED' }]
const typeOptions = [{ label: '剧本解析', value: 'SCRIPT_ANALYSIS' }, { label: '资产提取', value: 'ASSET_EXTRACTION' }, { label: '画面生成', value: 'IMAGE_GENERATION' }, { label: '图片编辑', value: 'IMAGE_EDIT' }, { label: '角色配音', value: 'VOICEOVER' }, { label: '音乐生成', value: 'MUSIC' }, { label: '音效生成', value: 'SOUND_EFFECT' }, { label: '字幕生成', value: 'SUBTITLE' }, { label: '视频合成', value: 'COMPOSITION' }]
const episodeOptions = computed(() => episodes.value.map((episode) => ({ label: `第 ${episode.episodeNo} 集 · ${episode.title}`, value: Number(episode.id) })))
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const activeCount = computed(() => Object.entries(counts.value).filter(([status]) => ['PENDING', 'QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(status)).reduce((sum, [, value]) => sum + value, 0))
const summary = computed(() => [
  { key: 'active', label: '活动任务', value: activeCount.value, color: 'text-[#6654ee]' },
  { key: 'queued', label: '排队中', value: (counts.value.PENDING || 0) + (counts.value.QUEUED || 0), color: 'text-[#d0872b]' },
  { key: 'success', label: '已完成', value: counts.value.SUCCEEDED || 0, color: 'text-[#2f9a73]' },
  { key: 'failed', label: '失败', value: counts.value.FAILED || 0, color: 'text-[#c56b73]' },
])

async function loadTasks() {
  const sequence = ++requestSequence
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) return
  loading.value = true
  errorMessage.value = ''
  try {
    const params: Record<string, unknown> = { page: page.value, page_size: pageSize.value }
    if (filters.status) params.status = filters.status
    if (filters.taskType) params.task_type = filters.taskType
    if (filters.episodeId) params.episode_id = filters.episodeId
    if (filters.activeOnly) params.status = ['PENDING', 'QUEUED', 'RUNNING', 'CANCEL_REQUESTED']
    const response = await http.get<TaskPage>(`/projects/${id}/tasks`, params)
    if (sequence !== requestSequence) return
    tasks.value = response.list || []
    total.value = response.total || 0
    page.value = response.page || 0
    pageSize.value = response.page_size || 20
    counts.value = response.counts || {}
    if (selectedTask.value && detailVisible.value) {
      try { selectedTask.value = await http.get<TaskItem>(`/projects/${id}/tasks/${selectedTask.value.id}`) } catch { /* 保留上一次详情 */ }
    } else if (selectedTask.value) {
      const current = tasks.value.find((task) => task.id === selectedTask.value?.id)
      if (current) selectedTask.value = current
    }
  } catch (error) {
    if (sequence === requestSequence) errorMessage.value = error instanceof Error ? error.message : '任务加载失败'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function openDetail(task: TaskItem) {
  selectedTask.value = task
  detailVisible.value = true
  const id = Number(projectId.value)
  try {
    const response = await http.get<TaskItem>(`/projects/${id}/tasks/${task.id}`)
    selectedTask.value = response
  } catch (error) {
    message.warning(error instanceof Error ? error.message : '任务详情加载失败')
  }
}

async function cancelTask(task: TaskItem) {
  if (!task.can_cancel || !window.confirm(`确定取消“${task.title}”吗？`)) return
  try {
    const response = await http.post<{ id: number; status: string; can_cancel: boolean }>(`/projects/${Number(projectId.value)}/tasks/${task.id}/cancel`)
    task.status = response.status
    task.can_cancel = response.can_cancel
    if (selectedTask.value?.id === task.id) selectedTask.value.status = response.status
    message.success(response.status === 'CANCEL_REQUESTED' ? '已发起取消，任务正在停止' : '任务已取消')
    await loadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '任务取消失败')
  }
}

async function retryTask(task: TaskItem) {
  if (task.task_type !== 'SCRIPT_ANALYSIS' || task.status !== 'FAILED' || retryingTaskId.value !== null) return
  retryingTaskId.value = task.id
  try {
    const response = await http.post<{ task_id: string; status: string }>(`/projects/${Number(projectId.value)}/script/analysis-tasks/${task.id}/retry`)
    const nextTaskId = Number(response.task_id)
    if (Number.isInteger(nextTaskId) && nextTaskId > 0) {
      selectedTask.value = await http.get<TaskItem>(`/projects/${Number(projectId.value)}/tasks/${nextTaskId}`)
    }
    message.success('已发起重试')
    await loadTasks()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '发起重试失败')
  } finally {
    retryingTaskId.value = null
  }
}

function resetAndLoad() { page.value = 0; loadTasks() }
function changePage(next: number) { page.value = next; loadTasks() }
function statusLabel(value: string) { return Object.fromEntries(statusOptions.map((item) => [item.value, item.label]))[value] || value }
function typeLabel(value: string) { return Object.fromEntries(typeOptions.map((item) => [item.value, item.label]))[value] || value }
function stageLabel(value: string) { return ({ RUNNING: '正在执行', ANALYZE_CHARACTER: '正在解析角色', ANALYZE_SCENE_BACKGROUND: '正在解析场景', ANALYZE_PROP: '正在解析道具', RESULT_STAGED: '解析结果已暂存', PARTIAL_RESULT_STAGED: '部分解析结果已暂存', FAILED: '执行失败', QUEUED: '排队中' } as Record<string, string>)[value] || value.replaceAll('_', ' ').toLowerCase().replace(/(^| )\S/g, (char) => char.toUpperCase()) }
function eventLabel(value: string) { return ({ CREATED: '已创建', ENQUEUED: '已入队', STARTED: '开始执行', PROGRESS: '执行进度', CANCEL_REQUESTED: '请求取消', CANCELLED: '已取消', SUCCEEDED: '已完成', FAILED: '执行失败' } as Record<string, string>)[value] || value }
function analysisTypeLabel(value: string) { return ({ CHARACTER: '角色', SCENE_BACKGROUND: '场景', PROP: '道具' } as Record<string, string>)[value] || value }
function analysisStatusLabel(value: string) { return ({ PENDING: '待处理', RUNNING: '解析中', SUCCEEDED: '已完成', FAILED: '失败' } as Record<string, string>)[value] || value }
function analysisStatusType(value: string): 'default' | 'success' | 'warning' | 'error' | 'info' { return ({ SUCCEEDED: 'success', FAILED: 'error', RUNNING: 'info', PENDING: 'default' } as Record<string, 'default' | 'success' | 'warning' | 'error' | 'info'>)[value] || 'default' }
function taskIcon(value: string) { return value.includes('IMAGE') ? '▣' : value === 'COMPOSITION' ? '✦' : value === 'SCRIPT_ANALYSIS' ? '≡' : '♫' }
function statusType(value: string): 'default' | 'success' | 'warning' | 'error' | 'info' { return ({ SUCCEEDED: 'success', FAILED: 'error', CANCELLED: 'default', CANCEL_REQUESTED: 'warning', RUNNING: 'info', QUEUED: 'warning', PENDING: 'default' } as Record<string, 'default' | 'success' | 'warning' | 'error' | 'info'>)[value] || 'default' }
function progressStatus(value: string): 'default' | 'success' | 'warning' | 'error' { return value === 'FAILED' ? 'error' : value === 'SUCCEEDED' ? 'success' : value === 'CANCELLED' ? 'warning' : 'default' }
function formatDate(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '--' }

onMounted(async () => {
  drawerWidth.value = Math.min(470, Math.max(320, window.innerWidth - 24))
  try { await loadEpisodes(projectId.value) } catch { /* keep local episode context */ }
  await loadTasks()
  pollTimer = setInterval(() => {
    if (document.visibilityState === 'visible' && activeCount.value > 0) loadTasks()
  }, 4000)
})
onBeforeUnmount(() => { if (pollTimer) clearInterval(pollTimer) })
</script>
