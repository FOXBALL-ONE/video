<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-7 border-b border-[#e5e8ef] pb-8 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">VIDEO WORKSPACE · PROJECT
            {{ activeProject?.code || '--' }}</p>
          <h1 class="mt-5 text-[31px] font-medium leading-[1.18]">{{ activeProject?.name || '项目工作台' }}</h1>
          <p class="mt-3 max-w-[620px] text-[13px] leading-6 text-[#8a94aa]">{{ activeProject?.description }}
            从故事到成片，在一个工作台里推进每个制作节点。</p>
        </div>
        <div class="flex w-full gap-2 sm:w-auto">
          <n-button class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿</n-button>
          <n-button class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none"
                    color="#6654ee" type="primary" @click="openNextStep">
            继续制作
          </n-button>
        </div>
      </header>

      <section aria-label="项目概览" class="mt-7 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <n-card v-for="stat in overviewStats" :key="stat.label" :content-style="{ padding: '17px 18px' }"
                class="!rounded-[13px] !border-[#dfe3eb]">
          <span class="text-[11px] text-[#8994a8]">{{ stat.label }}</span>
          <div class="mt-2 flex items-baseline gap-2">
            <strong class="text-[23px] font-semibold text-[#303b51]">{{ stat.value }}</strong>
            <span class="text-[11px] text-[#929bad]">{{ stat.detail }}</span>
          </div>
        </n-card>
      </section>

      <section class="mt-7 grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        <n-card :content-style="{ padding: '0 24px 24px' }" class="!rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[108px] flex-col items-start justify-between gap-4 border-b border-[#e8eaf0] px-1 py-5 sm:flex-row sm:items-center sm:px-2">
            <div>
              <h2 class="m-0 text-lg font-medium">制作流程</h2>
              <p class="mt-2 text-xs text-[#8994a8]">
                线性推进 7 个节点，做到哪里就点亮哪里<span v-if="activeWorkflow">（当前：{{ activeWorkflow.episode.title }}）</span>。
              </p>
            </div>
            <div class="flex items-center gap-3">
              <span class="text-[11px] text-[#a0a8b8]">{{ completedStepCount }} / {{ steps.length }} 节点已完成 · 整体 {{ overallProgress }}%</span>
              <n-tag :bordered="false" :type="activeProject?.status === '草稿' ? 'default' : 'success'"
                     class="!rounded-md">{{ activeProject?.status || '草稿' }} ·
                {{ activeProject?.version || '--' }}
              </n-tag>
            </div>
          </div>

          <div class="grid grid-cols-1 gap-3 pt-5 sm:grid-cols-2">
            <NuxtLink
                v-for="(step, index) in steps"
                :key="step.id"
                :to="step.route"
                class="route-card group flex min-h-[145px] flex-col rounded-[13px] border p-4 transition-all hover:-translate-y-0.5 hover:border-[#776cff] hover:shadow-[0_10px_24px_rgba(91,79,235,0.1)] focus:outline-none focus:ring-2 focus:ring-[#8f87ff] focus:ring-offset-2"
                :class="step.state === 'completed' ? 'border-[#cdebd8] bg-[#f5fbf7] hover:bg-white' : step.state === 'active' ? '!border-[#c7bfff] bg-[#f8f6ff] shadow-[0_8px_20px_rgba(91,79,235,0.08)] hover:bg-white' : 'border-[#dfe3eb] bg-white'"
                :aria-current="step.state === 'active' ? 'step' : undefined"
            >
              <div class="flex items-start justify-between">
                <span
                    class="flex h-9 w-9 items-center justify-center rounded-[10px] text-[19px]"
                    :class="step.state === 'completed' ? 'bg-[#e9f7f1] text-[#278d68]' : step.state === 'active' ? 'bg-[#eceaff] text-[#5b4ff0]' : 'bg-[#f3f4f8] text-[#98a0af]'">{{ step.icon }}</span>
                <span class="text-[11px] font-semibold text-[#a0a8b8]">{{ String(index + 1).padStart(2, '0') }} / {{ String(steps.length).padStart(2, '0') }}</span>
              </div>
              <strong class="mt-4 text-sm font-semibold text-[#303b51] group-hover:text-[#594df0]">{{
                  step.title
                }}</strong>
              <span class="mt-1.5 text-xs text-[#8994a8]">{{ step.caption }}</span>
              <div class="mt-3 flex items-center justify-between gap-3 text-[11px]">
                <span class="truncate" :class="step.state === 'pending' ? 'text-[#a0a8b8]' : 'text-[#687388]'">{{ step.detail }}</span>
                <span class="shrink-0 tabular-nums" :class="step.state === 'pending' ? 'text-[#b3b9c6]' : 'text-[#5b4ff0]'">{{ step.progress }}%</span>
              </div>
              <span class="mt-2 block h-1.5 overflow-hidden rounded-full bg-[#ececf4]" aria-hidden="true">
                <span class="block h-full rounded-full transition-all duration-500"
                      :class="step.state === 'active' ? 'bg-[#6654ee]' : step.state === 'completed' ? 'bg-[#2eae72]' : 'bg-[#d7dbe4]'"
                      :style="{ width: `${step.progress}%` }"></span>
              </span>
              <div class="mt-auto flex items-center justify-between pt-4">
                <span class="flex items-center gap-1.5 text-[11px] font-medium"
                      :class="step.state === 'completed' ? 'text-[#278d68]' : step.state === 'active' ? 'text-[#5b4ff0]' : 'text-[#98a0af]'">
                  <span class="flex h-4 w-4 items-center justify-center rounded-full text-[9px] font-semibold"
                        :class="step.state === 'completed' ? 'bg-[#2eae72] text-white' : step.state === 'active' ? 'bg-[#6654ee] text-white' : 'bg-[#f0f1f4] text-[#8b93a2]'">{{ badge(step, index) }}</span>
                  {{ step.state === 'completed' ? '节点已完成' : step.state === 'active' ? '进行中' : '待开始' }}
                </span>
                <span aria-hidden="true" class="text-xs text-[#6654ee]">进入节点 ↗</span>
              </div>
            </NuxtLink>
          </div>
        </n-card>

        <n-card :content-style="{ padding: 0 }" class="!rounded-[17px] !border-[#dfe3eb]">
          <div class="border-b border-[#e8eaf0] px-5 py-[18px]">
            <h2 class="m-0 text-[13px] font-semibold">项目状态</h2>
            <p class="mt-1.5 text-[11px] text-[#8e98aa]">{{ syncLabel }}</p>
          </div>
          <div class="space-y-5 px-5 py-5">
            <div>
              <div class="flex items-center justify-between text-xs"><span
                  class="text-[#687388]">整体完成度</span><strong class="text-[#5a4ef0]">{{
                  overallProgress
                }}%</strong></div>
              <n-progress :percentage="overallProgress" :show-indicator="false" class="mt-3" color="#6654ee"
                          rail-color="#ececf4" type="line"/>
            </div>
            <div class="rounded-[11px] px-3 py-3 text-[11px] leading-5"
                 :class="attentionSteps.length ? 'bg-[#fff9ed] text-[#9a7b48]' : 'bg-[#f5fbf7] text-[#3f8b6a]'">
              <strong :class="attentionSteps.length ? 'text-[#ae7620]' : 'text-[#278d68]'">{{ attentionLabel }}</strong>
              <p class="m-0 mt-1">{{ attentionDetail }}</p>
            </div>
            <div>
              <span class="text-[11px] text-[#8994a8]">版本信息</span>
              <dl class="mt-3 space-y-2 text-xs">
                <div class="flex justify-between gap-3">
                  <dt class="text-[#929bad]">当前版本</dt>
                  <dd class="m-0 font-medium text-[#4b566b]">{{ activeProject?.version || '--' }} 编辑稿</dd>
                </div>
                <div class="flex justify-between gap-3">
                  <dt class="text-[#929bad]">最后编辑</dt>
                  <dd class="m-0 font-medium text-[#4b566b]">{{ activeProject?.updatedAt || '--' }}</dd>
                </div>
                <div class="flex justify-between gap-3">
                  <dt class="text-[#929bad]">质量评分</dt>
                  <dd class="m-0 font-medium text-[#4b566b]">{{ activeProject?.score || '--' }} / 10</dd>
                </div>
              </dl>
            </div>
          </div>
          <div class="border-t border-[#e8eaf0] px-5 py-4">
            <n-button block class="!h-9 !rounded-[9px] !border-[#d8d2ff] !text-[#5d52ec]" ghost @click="openScript">
              查看项目详情
            </n-button>
          </div>
        </n-card>
      </section>

      <section class="mt-5 grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        <n-card :content-style="{ padding: '18px 20px' }" class="!rounded-[15px] !border-[#dfe3eb]">
          <div class="flex items-center justify-between">
            <h2 class="m-0 text-[13px] font-semibold">剧集进度</h2>
            <span class="text-[11px] text-[#929bad]">共 {{ episodes.length }} 集</span>
          </div>
          <div v-if="episodeProgress.length" class="mt-4 space-y-3.5">
            <div v-for="item in episodeProgress" :key="item.id" class="text-xs">
              <div class="flex items-center justify-between gap-3">
                <span class="flex min-w-0 items-center gap-2">
                  <strong class="truncate font-medium text-[#4b566b]">{{ item.title }}</strong>
                  <span v-if="item.id === activeEpisodeId" class="shrink-0 rounded-md bg-[#eceaff] px-1.5 py-0.5 text-[10px] text-[#5d52dd]">当前</span>
                </span>
                <span class="shrink-0 tabular-nums text-[11px] text-[#5b4ff0]">{{ item.percent }}%</span>
              </div>
              <div class="mt-2 h-1.5 overflow-hidden rounded-full bg-[#ececf4]">
                <div class="h-full rounded-full bg-[#6654ee] transition-all duration-500" :style="{ width: `${item.percent}%` }"></div>
              </div>
              <div class="mt-1.5 flex items-center justify-between text-[11px] text-[#929bad]">
                <span>{{ item.caption }}</span>
                <span>{{ item.updatedAt }}</span>
              </div>
            </div>
          </div>
          <p v-else class="mt-4 text-xs text-[#a0a8b8]">还没有剧集，先创建剧集再推进制作节点。</p>
        </n-card>

        <n-card :content-style="{ padding: '18px 20px' }" class="!rounded-[15px] !border-[#dfe3eb]">
          <div class="flex items-center justify-between">
            <h2 class="m-0 text-[13px] font-semibold">下一步建议</h2>
            <span class="text-[11px] text-[#929bad]">按流程</span>
          </div>
          <div v-if="nextActions.length" class="mt-4 space-y-2">
            <button
                v-for="action in nextActions"
                :key="action.label"
                type="button"
                class="flex w-full flex-col items-start gap-1 rounded-[10px] border border-[#e0ddff] px-3 py-2.5 text-left transition-colors hover:bg-[#f4f2ff] focus:outline-none focus:ring-2 focus:ring-[#8f87ff] focus:ring-offset-1"
                @click="jumpTo(action.step)"
            >
              <span class="flex w-full items-center justify-between gap-2">
                <strong class="text-xs font-medium text-[#5d52ec]">{{ action.label }}</strong>
                <span class="shrink-0 text-[10px] text-[#a0a8b8]">{{ action.step.progress }}%</span>
              </span>
              <small class="text-[10px] leading-4 text-[#929bad]">{{ action.detail }}</small>
            </button>
          </div>
          <p v-else class="mt-4 text-xs leading-5 text-[#3f8b6a]">全部节点已完成，可以进入成片质检与导出交付。</p>
        </n-card>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import {computed, onMounted, ref, watch} from 'vue'
import type {WorkspaceEpisode} from '~/stores/episode'

type StepId = 'script' | 'tasks' | 'assets' | 'shots' | 'storyboard' | 'video' | 'export'
type StepState = 'completed' | 'active' | 'pending'

interface StepSnapshot {
  ratio: number
  detail: string
  scriptStatus?: string
  cardStatus?: string
  readyCount?: number
  assetTotal?: number
  sceneTotal?: number
  shotTotal?: number
  promptReady?: number
  videoDone?: number
  videoTotal?: number
}

interface StepDefinition {
  id: StepId
  title: string
  caption: string
  icon: string
  route: (base: string) => string
}

interface EpisodeWorkflow {
  id: string
  episode: WorkspaceEpisode
  steps: Record<StepId, StepSnapshot>
}

interface WorkflowStep extends StepDefinition, StepSnapshot {
  state: StepState
  progress: number
  route: string
}

const message = useMessage()
const http = useHttp()
const {activeProject, activeProjectId} = useProjectWorkspace()
const {episodes, activeEpisode, activeEpisodeId, loadEpisodes} = useEpisodeWorkspace()
const route = useRoute()

const workflowLoading = ref(false)
const syncedAt = ref<Date | null>(null)
const workflows = ref<EpisodeWorkflow[]>([])
let refreshPending = false
const projectBase = computed(() => `/projects/${String(route.params.projectId || activeProject.value?.id || '')}`)
const projectKey = computed(() => String(route.params.projectId || activeProject.value?.id || ''))
const scopedEpisode = computed(() => {
  if (/^\d+$/.test(String(route.params.episodeId || ''))) return String(route.params.episodeId)
  return String(activeEpisodeId.value || episodes.value[0]?.id || '')
})
const workflowBase = computed(() => scopedEpisode.value ? `${projectBase.value}/episodes/${scopedEpisode.value}/workflow` : `${projectBase.value}/workflow`)

const stageOrder = ['SCRIPT', 'ASSETS', 'SHOTS', 'VIDEO']
const stepNodes: StepDefinition[] = [
  { id: 'script', title: '剧本导入', caption: '导入剧本或小说，识别场次、角色与对白', icon: '✦', route: (base) => `${base}/script` },
  { id: 'tasks', title: '每集任务卡', caption: '每集主目标、剧情边界与前后集衔接', icon: '▣', route: (base) => `${base}/tasks` },
  { id: 'assets', title: '资产管理', caption: '角色、场景背景、道具与服饰', icon: '◇', route: (base) => `${base}/assets` },
  { id: 'shots', title: '分镜规划', caption: '拆解镜头，记录画面、调度与声音', icon: '▦', route: (base) => `${base}/shots` },
  { id: 'storyboard', title: '分镜图生成', caption: '生成并确认每个镜头的画面', icon: '▨', route: (base) => `${base}/storyboard` },
  { id: 'video', title: '画面视频', caption: '画面、配音、音效与合成', icon: '▶', route: (base) => `${base}/video` },
  { id: 'export', title: '合成导出', caption: '成片质检与导出交付', icon: '↧', route: (base) => `${base}/video` }
]

function emptySteps(): Record<StepId, StepSnapshot> {
  return {
    script: { ratio: 0, detail: '待导入剧本', sceneTotal: 0, shotTotal: 0, assetTotal: 0, videoTotal: 0, videoDone: 0 },
    tasks: { ratio: 0, detail: '任务卡未创建' },
    assets: { ratio: 0, detail: '暂无资产', readyCount: 0, assetTotal: 0 },
    shots: { ratio: 0, detail: '暂无镜头', shotTotal: 0, promptReady: 0 },
    storyboard: { ratio: 0, detail: '暂无分镜图', shotTotal: 0, promptReady: 0 },
    video: { ratio: 0, detail: '制作队列未启动', videoTotal: 0, videoDone: 0 },
    export: { ratio: 0, detail: '待成片确认' }
  }
}

async function refresh() {
  if (workflowLoading.value) {
    refreshPending = true
    return
  }
  workflowLoading.value = true
  try {
    try {
      await loadEpisodes(projectKey.value)
    } catch {
      // 接口不可用时保留本地剧集数据，节点状态回退到剧集进度字段。
    }
    workflows.value = await loadWorkflows()
    syncedAt.value = new Date()
  } finally {
    workflowLoading.value = false
    if (refreshPending) {
      refreshPending = false
      void refresh()
    }
  }
}

/** 汇总每个剧集的真实产出，作为节点点亮的依据。 */
async function loadWorkflows(): Promise<EpisodeWorkflow[]> {
  const project = Number(projectKey.value)
  if (!Number.isInteger(project) || project <= 0) return []
  const queue = [...episodes.value]
  const results: EpisodeWorkflow[] = []
  const worker = async () => {
    for (let episode = queue.shift(); episode; episode = queue.shift()) {
      const episodeId = Number(episode.id)
      if (!Number.isInteger(episodeId) || episodeId <= 0) {
        results.push({ id: episode.id, episode, steps: emptySteps() })
        continue
      }
      results.push({ id: episode.id, episode, steps: await loadEpisodeStepSnapshots(project, episodeId) })
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, queue.length) }, worker))
  return results.sort((a, b) => a.episode.episodeNo - b.episode.episodeNo)
}

async function loadEpisodeStepSnapshots(project: number, episodeId: number): Promise<Record<StepId, StepSnapshot>> {
  const [script, card, assets, shots, storyboards, video] = await Promise.all([
    rejectable(http.get<ScriptSummary>(`/projects/${project}/script`, { episode_id: episodeId })),
    rejectable(http.get<TaskCardSummary>(`/projects/${project}/episodes/${episodeId}/task-card`)),
    rejectable(http.get<AssetSummary>(`/projects/${project}/episodes/${episodeId}/assets`)),
    rejectable(http.get<ShotSummary>(`/projects/${project}/shots`, { episode_id: episodeId })),
    rejectable(http.get<BatchSummary>(`/projects/${project}/episodes/${episodeId}/storyboards`)),
    rejectable(http.get<VideoSummary>(`/projects/${project}/video`, { episode_id: episodeId }))
  ])

  const steps = emptySteps()

  const sceneTotal = script?.stats?.scene_count ?? 0
  const hasScript = Boolean(script?.content?.trim())
  steps.script = {
    ratio: analysed ? 100 : 0,
    detail: !hasScript ? '尚未导入剧本正文' : analysed ? `${sceneTotal} 场 · ${script?.stats?.character_count ?? 0} 位角色` : scripting ? '解析任务正在运行' : '正文已保存，等待解析',
    scriptStatus: script?.analysis?.status || (hasScript ? 'SAVED' : 'NONE'),
    sceneTotal,
    shotTotal: 0,
    assetTotal: 0,
    videoTotal: 0,
    videoDone: 0
  }

  const cardCompletion = card?.card?.completion ?? 0
  const cardLocked = String(card?.card?.status || '') === 'LOCKED'
  steps.tasks = {
    ratio: cardCompletion || (cardLocked ? 100 : 0),
    detail: cardLocked ? `已锁定 v${card?.card?.version_no ?? 1}` : cardCompletion ? `草稿完成 ${cardCompletion}%` : '尚未填写任务卡',
    cardStatus: card?.card?.status || 'NONE'
  }

  const assetTotal = assets?.list?.length ?? 0
  const readyCount = (assets?.list || []).filter((asset) => ['LOCKED', 'READY'].includes(String(asset.status))).length
  steps.assets = {
    ratio: assets?.completeness ?? 0,
    detail: assetTotal ? `${readyCount} / ${assetTotal} 项已就绪` : '尚未提取剧集资产',
    readyCount,
    assetTotal
  }

  const shotList = shots?.list || []
  const promptReady = shotList.filter((shot) => Boolean(String(shot.prompt || '').trim())).length
  steps.shots = {
    ratio: shotList.length ? Math.round(promptReady * 100 / shotList.length) : 0,
    detail: shotList.length ? `${promptReady} / ${shotList.length} 镜已确认提示词` : '尚未拆解镜头',
    shotTotal: shotList.length,
    promptReady
  }

  const batches = storyboards?.list || []
  const mapped = new Set<number>()
  for (const batch of batches) {
    for (const shotId of batch.imported_shot_ids || []) mapped.add(Number(shotId))
  }
  const filled = shotList.filter((shot) => mapped.has(Number(shot.id)) || Boolean(String(shot.storyboard_file_id || ''))).length
  const generated = batches.filter((batch) => ['GENERATED', 'IMPORTED'].includes(String(batch.status))).length
  const filledRatio = shotList.length ? Math.round(filled * 100 / shotList.length) : 0
  const generatedRatio = batches.length ? Math.round(generated * 100 / batches.length) : 0
  steps.storyboard = {
    // 已填入的镜头决定完成度，仅生成画板时按批次给出阶段性进度。
    ratio: filledRatio > 0 || !batches.length ? filledRatio : Math.min(generatedRatio, 90),
    detail: shotList.length ? `${filled} / ${shotList.length} 镜已填入 · ${generated} / ${batches.length} 批已生成` : '待生成九宫格',
    shotTotal: shotList.length,
    promptReady: filled
  }

  const tasksList = video?.tasks || []
  const videoDone = tasksList.filter((task) => task.status === 'SUCCEEDED').length
  const videoRunning = tasksList.some((task) => ['RUNNING', 'QUEUED', 'PENDING'].includes(task.status))
  const videoRatio = tasksList.length ? Math.round(videoDone * 100 / tasksList.length) : 0
  steps.video = {
    ratio: videoRatio,
    detail: tasksList.length ? `${videoDone} / ${tasksList.length} 项制作任务已完成` : '尚未启动制作队列',
    videoTotal: tasksList.length,
    videoDone
  }
  steps.export = {
    ratio: videoRatio,
    detail: videoRatio >= 100 ? '成片待质检与导出' : videoRunning ? '等待制作队列完成' : '尚未开始合成导出',
    videoTotal: tasksList.length,
    videoDone
  }

  return steps
}

function rejectable<T>(promise: Promise<T>): Promise<T | null> {
  return promise.catch(() => null)
}

const activeWorkflow = computed(() => {
  const scoped = scopedEpisode.value
  if (scoped) return workflows.value.find((item) => item.id === scoped) || null
  return workflows.value.find((item) => item.id === activeEpisode.value?.id) || workflows.value[0] || null
})

const projectSteps = computed<Record<StepId, StepSnapshot>>(() => {
  const list = workflows.value
  if (!list.length) return emptySteps()
  return {
    script: aggregate(list, 'script'),
    tasks: aggregate(list, 'tasks'),
    assets: aggregate(list, 'assets'),
    shots: aggregate(list, 'shots'),
    storyboard: aggregate(list, 'storyboard'),
    video: aggregate(list, 'video'),
    export: aggregate(list, 'export')
  }
})

function aggregate(list: EpisodeWorkflow[], id: StepId): StepSnapshot {
  const snapshots = list.map((item) => item.steps[id])
  const ratio = Math.round(snapshots.reduce((sum, item) => sum + item.ratio, 0) / snapshots.length)
  if (id === 'assets') {
    const assetTotal = snapshots.reduce((sum, item) => sum + (item.assetTotal || 0), 0)
    const readyCount = snapshots.reduce((sum, item) => sum + (item.readyCount || 0), 0)
    return { ratio, detail: assetTotal ? `${list.length} 集共 ${assetTotal} 项资产 · ${readyCount} 项就绪` : '尚未提取剧集资产', readyCount, assetTotal }
  }
  if (id === 'shots') {
    const shotTotal = snapshots.reduce((sum, item) => sum + (item.shotTotal || 0), 0)
    const promptReady = snapshots.reduce((sum, item) => sum + (item.promptReady || 0), 0)
    return { ratio, detail: shotTotal ? `${list.length} 集共 ${shotTotal} 镜 · ${promptReady} 镜已确认提示词` : '尚未拆解镜头', shotTotal, promptReady }
  }
  if (id === 'storyboard') {
    const shotTotal = snapshots.reduce((sum, item) => sum + (item.shotTotal || 0), 0)
    const filled = snapshots.reduce((sum, item) => sum + (item.promptReady || 0), 0)
    return { ratio, detail: shotTotal ? `${list.length} 集已填入 ${filled} / ${shotTotal} 镜画面` : '待生成九宫格', shotTotal, promptReady: filled }
  }
  if (id === 'video') {
    const videoTotal = snapshots.reduce((sum, item) => sum + (item.videoTotal || 0), 0)
    const videoDone = snapshots.reduce((sum, item) => sum + (item.videoDone || 0), 0)
    return { ratio, detail: videoTotal ? `${list.length} 集共 ${videoTotal} 项任务 · ${videoDone} 项完成` : '尚未启动制作队列', videoTotal, videoDone }
  }
  if (id === 'export') {
    const finished = list.filter((item) => item.steps.video.ratio >= 100 && (item.steps.video.videoTotal || 0) > 0).length
    return { ratio, detail: finished ? `${finished} / ${list.length} 集制作完成，待质检导出` : '尚未开始合成导出' }
  }
  if (id === 'tasks') {
    const filled = list.filter((item) => item.steps.tasks.cardStatus && item.steps.tasks.cardStatus !== 'NONE').length
    const locked = list.filter((item) => item.steps.tasks.cardStatus === 'LOCKED').length
    return { ratio, detail: filled ? `${filled} / ${list.length} 集已填写 · ${locked} 集已锁定` : '尚未填写任务卡' }
  }
  const sceneTotal = list.reduce((sum, item) => sum + (item.steps.script.sceneTotal || 0), 0)
  const analysed = list.filter((item) => ['COMPLETED', 'SUCCEEDED'].includes(String(item.steps.script.scriptStatus || ''))).length
  return { ratio, detail: analysed ? `${analysed} / ${list.length} 集已解析 · ${sceneTotal} 场` : '尚未导入或解析剧本' }
}

/** 节点状态以真实产出的完成度为准；剧集节点字段用于兜底，避免接口缺失时全部熄灭。 */
const steps = computed<WorkflowStep[]>(() => {
  const workflow = activeWorkflow.value
  const snapshotSource = workflow?.steps || projectSteps.value
  const stageLevel = Math.max(0, stageOrder.indexOf(String(workflow?.episode.currentStage || 'SCRIPT')))
  return stepNodes.map((node, index) => {
    const snapshot = snapshotSource[node.id]
    const stageIndex = node.id === 'tasks' ? 0 : Math.max(0, ['script', 'assets', 'shots', 'storyboard', 'video', 'export'].indexOf(node.id) - 1)
    const stageCompleted = Boolean(workflow) && index > 0 && stageIndex < stageLevel
    const completed = snapshot.ratio >= 100 || stageCompleted
    const active = !completed && (index === 0 || (snapshotSource[stepNodes[index - 1].id]?.ratio || 0) > 0 || stageIndex <= stageLevel)
    const state: StepState = completed ? 'completed' : active ? 'active' : 'pending'
    const progress = completed ? 100 : active ? snapshot.ratio : 0
    return {
      ...node,
      ...snapshot,
      route: node.route(workflowBase.value),
      state,
      progress
    }
  })
})

const completedStepCount = computed(() => steps.value.filter((step) => step.state === 'completed').length)
const overallProgress = computed(() => Math.round(steps.value.reduce((sum, step) => sum + step.progress, 0) / steps.value.length))
const activeStep = computed(() => steps.value.find((step) => step.state === 'active') || null)
const attentionSteps = computed(() => steps.value.filter((step) => step.state !== 'completed'))
const attentionLabel = computed(() => attentionSteps.value.length ? `${attentionSteps.value.length} 个节点待推进` : '全部节点已完成')
const attentionDetail = computed(() => attentionSteps.value.length
    ? `当前建议先处理：${activeStep.value?.title || attentionSteps.value[0].title}`
    : '可以进入合成导出与成片质检。')
const syncLabel = computed(() => syncedAt.value
    ? `最近一次同步 · ${syncedAt.value.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`
    : workflowLoading.value ? '正在同步节点进度...' : '未同步节点进度')

const episodeProgress = computed(() => episodes.value.map((episode) => {
  const workflow = workflows.value.find((item) => item.id === episode.id)
  const percent = workflow ? Math.round(Object.values(workflow.steps).reduce((sum, snapshot) => sum + snapshot.ratio, 0) / stepNodes.length) : episode.progress
  return {
    id: episode.id,
    title: episode.title,
    percent,
    caption: stageLabel(episode.currentStage),
    updatedAt: episode.updatedAt
  }
}))

const overviewStats = computed(() => [
  { label: '制作剧集', value: String(episodes.value.length), detail: '集' },
  { label: '整体完成度', value: `${overallProgress.value}%`, detail: `${completedStepCount.value}/${steps.value.length} 节点` },
  { label: '总时长', value: formatDuration(episodes.value.reduce((sum, episode) => sum + (episode.durationSeconds || 0), 0)), detail: '素材' },
  { label: '质量评分', value: activeProject.value?.score || '--', detail: '/ 10' }
])

const nextActions = computed(() => {
  const current = activeStep.value
  if (!current) return []
  const currentIndex = steps.value.findIndex((step) => step.id === current.id)
  return steps.value.slice(currentIndex, currentIndex + 2).map((step, offset) => ({
    step,
    label: offset === 0 ? `继续 ${step.title}` : `准备 ${step.title}`,
    detail: step.detail
  }))
})

function badge(step: WorkflowStep, index: number) {
  if (step.state === 'completed') return '✓'
  return String(index + 1)
}

function stageLabel(stage: string) {
  return ({ SCRIPT: '剧本导入', ASSETS: '资产管理', SHOTS: '分镜规划', VIDEO: '画面视频' } as Record<string, string>)[stage] || '剧本导入'
}

function formatDuration(seconds: number) {
  const total = Math.max(0, Math.round(seconds))
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
}

function saveDraft() {
  message.success('草稿已保存')
}

function openNextStep() {
  const next = activeStep.value || steps.value.find((step) => step.state !== 'completed') || steps.value[steps.value.length - 1]
  navigateTo(next.route)
}

function openScript() {
  navigateTo(`${workflowBase.value}/script`)
}

function jumpTo(step: { route: string }) {
  navigateTo(step.route)
}

onMounted(() => {
  void refresh()
})

watch([projectKey, scopedEpisode], () => {
  workflows.value = []
  syncedAt.value = null
  void refresh()
})
</script>

<script lang="ts">
interface ScriptSummary {
  content?: string
  analysis?: { status?: string } | null
  stats?: { scene_count?: number; character_count?: number } | null
}

interface TaskCardSummary {
  card?: { status?: string; completion?: number; version_no?: number } | null
}

interface AssetSummary {
  list?: Array<{ id: number; status?: string }>
  completeness?: number
}

interface ShotSummary {
  list?: Array<{ id: number; prompt?: string | null; storyboard_file_id?: string | null }>
}

interface BatchSummary {
  list?: Array<{ status?: string; imported_shot_ids?: number[] }>
}

interface VideoSummary {
  tasks?: Array<{ status: string }>
}
</script>
