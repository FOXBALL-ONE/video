<template>
  <main class="task-page min-h-[calc(100vh-65px)] bg-[#f8f9fb] text-[#20283b]">
    <ProductionSteps active="tasks" :show-episode="false" />

    <div class="mx-auto max-w-6xl px-4 pb-24 pt-7 sm:px-6">
      <header class="mb-6 flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#6654ee]">02 / 07 · EPISODE WORKFLOW</p>
          <h1 class="mt-3 text-[30px] font-medium leading-tight">每集任务卡</h1>
          <p class="mt-2 max-w-2xl text-sm leading-6 text-[#737c8d]">每集一张卡：主目标、必须包含、禁止改动、前集摘要、下集目标和结尾钩子。锁定后下游分镜与视频制作都会遵守这张卡。</p>
        </div>
        <div v-if="activeEpisode" class="rounded-lg border border-[#dedaff] bg-[#f4f2ff] px-3 py-2 text-xs text-[#5d52dd]">当前剧集：<strong>{{ activeEpisode.title }}</strong></div>
      </header>

      <section class="grid grid-cols-2 gap-3 sm:grid-cols-4" aria-label="任务卡概览">
        <div v-for="stat in overviewStats" :key="stat.label" class="rounded-lg border border-[#dfe2e8] bg-white p-3">
          <span class="text-xs text-[#8992a3]">{{ stat.label }}</span>
          <strong class="mt-1 block text-lg font-semibold text-[#30394a]">{{ stat.value }}</strong>
        </div>
      </section>

      <section class="mt-4 rounded-lg border border-[#dfe2e8] bg-white p-3" aria-label="每集状态">
        <div class="mb-2 flex items-center justify-between"><span class="text-xs font-medium text-[#4f596d]">每集状态</span><span class="text-[11px] text-[#9299a7]">点击剧集切换编辑</span></div>
        <div class="grid grid-cols-5 gap-1.5 sm:grid-cols-10">
          <button v-for="item in summaryItems" :key="item.episode_id" type="button" class="h-7 rounded text-[10px] transition-colors" :class="item.episode_id === selectedEpisodeId ? 'bg-[#f28a21] font-semibold text-white' : item.status === 'LOCKED' ? 'bg-[#e9f7f1] text-[#278d68]' : item.id ? 'bg-[#f0eeff] text-[#5d52dd]' : 'bg-[#f1f2f5] text-[#8992a3]'" :title="`${item.title} · ${item.status || '未创建'}`" @click="selectEpisode(String(item.episode_id))">{{ item.episode_no }}</button>
        </div>
      </section>

      <section class="mt-4 overflow-hidden rounded-xl border border-[#dfe2e8] bg-white">
        <header class="flex flex-wrap items-center justify-between gap-3 border-b border-[#e5e7ec] px-4 py-3 sm:px-5">
          <div class="flex items-center gap-3"><span class="text-xl text-[#f28a21]" aria-hidden="true">▣</span><div><h2 class="m-0 text-sm font-semibold">每集任务卡 · {{ selectedEpisode?.episodeNo || '--' }}</h2><p class="mt-1 text-xs text-[#9299a7]">{{ createdCount }} / {{ episodes.length }} 张已创建 · {{ lockedCount }} 张已锁定</p></div></div>
          <div class="flex items-center gap-2">
            <button type="button" class="inline-flex h-8 items-center gap-1.5 rounded border border-[#dfe2e8] px-2.5 text-xs text-[#687388] hover:bg-[#f6f6f8]" @click="showHistory = !showHistory"><span aria-hidden="true">↶</span> 历史 {{ history.length }}</button>
            <button v-if="card?.status === 'LOCKED'" type="button" class="inline-flex h-8 items-center gap-1.5 rounded border border-[#dfe2e8] px-2.5 text-xs text-[#687388] hover:bg-[#f6f6f8]" @click="unlockCard"><span aria-hidden="true">⌑</span> 解锁</button>
            <button v-else type="button" class="inline-flex h-8 items-center gap-1.5 rounded border border-[#dfe2e8] px-2.5 text-xs text-[#687388] hover:bg-[#f6f6f8]" :disabled="!card || saving" @click="lockCard"><span aria-hidden="true">⌑</span> 锁定</button>
            <button type="button" class="inline-flex h-8 items-center gap-1.5 rounded bg-[#6654ee] px-3 text-xs font-medium text-white hover:bg-[#5848d6] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!card || saving || card.status === 'LOCKED'" @click="saveCard"><span aria-hidden="true">⌁</span> {{ saving ? '保存中...' : '保存' }}</button>
          </div>
        </header>

        <div class="flex min-h-[570px]">
          <aside class="hidden w-56 shrink-0 border-r border-[#e5e7ec] p-2 sm:block">
            <button v-for="episode in episodes" :key="episode.id" type="button" class="mb-0.5 flex w-full items-center justify-between gap-2 rounded px-3 py-2 text-left text-sm" :class="episode.id === selectedEpisodeId ? 'bg-[#fff4e8] font-medium text-[#d97617]' : 'text-[#687388] hover:bg-[#f6f6f8]'" @click="selectEpisode(episode.id)"><span class="min-w-0"><span class="block truncate">{{ episode.title }}</span><span class="mt-0.5 block truncate text-xs text-[#9299a7]">{{ episodeStatus(episode.id) }}</span></span><span aria-hidden="true" class="text-xs">›</span></button>
          </aside>

          <div class="min-w-0 flex-1 p-4 sm:p-6">
            <div v-if="loading" class="flex min-h-[520px] items-center justify-center text-sm text-[#8992a3]">正在加载任务卡...</div>
            <form v-else class="max-w-3xl space-y-4" @submit.prevent="saveCard">
              <label class="block"><span class="mb-1 block text-sm font-medium text-[#30394a]">主目标</span><textarea v-model="form.primaryGoal" :disabled="isLocked" rows="3" class="block w-full resize-y rounded border border-[#dfe2e8] px-3 py-2 text-sm leading-6 outline-none focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eeecff] disabled:bg-[#f6f7f9]" placeholder="本集的主要剧情目标"></textarea></label>

              <fieldset><legend class="mb-1 text-sm font-medium text-[#30394a]">必须包含</legend><div class="space-y-1.5"><div v-for="(_, index) in form.mustInclude" :key="`must-${index}`" class="flex items-center gap-2"><input v-model="form.mustInclude[index]" :disabled="isLocked" class="h-9 min-w-0 flex-1 rounded border border-[#dfe2e8] px-3 text-sm outline-none focus:border-[#9b91f5] disabled:bg-[#f6f7f9]" placeholder="如：女主找到藏在仓库的账本" /><button type="button" class="flex h-8 w-8 shrink-0 items-center justify-center rounded text-[#df6262] hover:bg-[#fff0f0] disabled:opacity-40" :disabled="isLocked || form.mustInclude.length <= 1" aria-label="删除必须包含条目" @click="removeItem(form.mustInclude, index)">×</button></div></div><button type="button" class="mt-2 text-xs text-[#6654ee] hover:text-[#5344d2] disabled:opacity-40" :disabled="isLocked" @click="addItem(form.mustInclude)">＋ 添加必须包含</button></fieldset>

              <fieldset><legend class="mb-1 text-sm font-medium text-[#30394a]">禁止改动</legend><div class="space-y-1.5"><div v-for="(_, index) in form.prohibitions" :key="`prohibit-${index}`" class="flex items-center gap-2"><input v-model="form.prohibitions[index]" :disabled="isLocked" class="h-9 min-w-0 flex-1 rounded border border-[#dfe2e8] px-3 text-sm outline-none focus:border-[#9b91f5] disabled:bg-[#f6f7f9]" placeholder="如：不得改变角色关系或世界观规则" /><button type="button" class="flex h-8 w-8 shrink-0 items-center justify-center rounded text-[#df6262] hover:bg-[#fff0f0] disabled:opacity-40" :disabled="isLocked || form.prohibitions.length <= 1" aria-label="删除禁止改动条目" @click="removeItem(form.prohibitions, index)">×</button></div></div><button type="button" class="mt-2 text-xs text-[#6654ee] hover:text-[#5344d2] disabled:opacity-40" :disabled="isLocked" @click="addItem(form.prohibitions)">＋ 添加禁止改动</button></fieldset>

              <div class="grid grid-cols-1 gap-4 md:grid-cols-2"><label class="block"><span class="mb-1 block text-sm font-medium text-[#30394a]">前集摘要</span><textarea v-model="form.previousSummary" :disabled="isLocked" rows="4" class="block w-full resize-y rounded border border-[#dfe2e8] px-3 py-2 text-sm leading-6 outline-none focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eeecff] disabled:bg-[#f6f7f9]" placeholder="前一集发生了什么"></textarea></label><label class="block"><span class="mb-1 block text-sm font-medium text-[#30394a]">下集目标</span><textarea v-model="form.nextGoal" :disabled="isLocked" rows="4" class="block w-full resize-y rounded border border-[#dfe2e8] px-3 py-2 text-sm leading-6 outline-none focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eeecff] disabled:bg-[#f6f7f9]" placeholder="为下一集要做的铺垫"></textarea></label></div>
              <label class="block"><span class="mb-1 block text-sm font-medium text-[#30394a]">结尾钩子</span><textarea v-model="form.endingHook" :disabled="isLocked" rows="3" class="block w-full resize-y rounded border border-[#dfe2e8] px-3 py-2 text-sm leading-6 outline-none focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eeecff] disabled:bg-[#f6f7f9]" placeholder="本集结尾设置的悬念 / 钩子"></textarea></label>
              <div class="flex items-center justify-between border-t border-[#e8eaf0] pt-3 text-xs text-[#9299a7]"><span>完成度 {{ card?.completion || 0 }}% · v{{ card?.version_no || 1 }}</span><span v-if="isLocked" class="text-[#278d68]">已锁定，解锁后可修改</span></div>
            </form>

            <aside v-if="showHistory" class="mt-5 rounded-lg border border-[#e5e7ec] bg-[#fbfbfd] p-3"><div class="mb-2 flex items-center justify-between"><strong class="text-xs text-[#4f596d]">历史版本</strong><button type="button" class="text-xs text-[#9299a7]" @click="showHistory = false">关闭</button></div><p v-if="!history.length" class="m-0 text-xs text-[#9299a7]">暂无历史版本</p><button v-for="revision in history" :key="revision.id" type="button" class="mb-1 block w-full rounded border border-[#e6e8ed] bg-white px-3 py-2 text-left text-xs hover:border-[#bdb5ff]" @click="useRevision(revision)"><span class="font-medium text-[#4f596d]">v{{ revision.version_no }}</span><span class="ml-2 text-[#9299a7]">{{ revision.created_at ? formatDate(revision.created_at) : '' }}</span><span class="mt-1 block truncate text-[#737c8d]">{{ revision.primary_goal || '未填写主目标' }}</span></button></aside>
          </div>
        </div>
      </section>
    </div>

    <footer class="fixed bottom-0 left-0 right-0 z-10 flex h-14 items-center justify-between border-t border-[#e1e3e8] bg-white px-5 sm:px-6"><button type="button" class="inline-flex h-10 items-center gap-1.5 rounded-md px-3 text-sm text-[#737c8d] hover:bg-[#f5f6f8]" @click="openStep('script')">‹ 上一步</button><button type="button" class="inline-flex h-10 items-center gap-1.5 rounded-md bg-[#f28a21] px-4 text-sm font-medium text-white hover:bg-[#df7914] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!card" @click="openStep('assets')">下一步 ›</button></footer>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'

interface TaskCard {
  id: number
  episode_id: number
  version_no: number
  primary_goal: string
  must_include: string[]
  prohibitions: string[]
  previous_summary: string
  next_goal: string
  ending_hook: string
  status: string
  completion: number
  locked_at: string | null
  updated_at: string | null
}

interface Revision extends Omit<TaskCard, 'id' | 'episode_id' | 'version_no' | 'status' | 'completion' | 'locked_at' | 'updated_at'> {
  id: number
  version_no: number
  created_at: string | null
}

interface SummaryItem {
  id: number
  episode_id: number
  episode_no: number
  title: string
  status: string | null
  completion: number
  updated_at: string | null
}

const http = useHttp()
const message = useMessage()
const route = useRoute()
const { activeProject } = useProjectWorkspace()
const { episodes, activeEpisodeId, activeEpisode, loadEpisodes, switchEpisode } = useEpisodeWorkspace()
const projectId = computed(() => String(route.params.projectId || activeProject.value?.id || ''))
const selectedEpisodeId = ref(String(route.params.episodeId || activeEpisodeId.value || ''))
const card = ref<TaskCard | null>(null)
const history = ref<Revision[]>([])
const summaryItems = ref<SummaryItem[]>([])
const loading = ref(false)
const saving = ref(false)
const showHistory = ref(false)
const form = reactive({ primaryGoal: '', mustInclude: [''], prohibitions: [''], previousSummary: '', nextGoal: '', endingHook: '' })
const isLocked = computed(() => card.value?.status === 'LOCKED')
const selectedEpisode = computed(() => episodes.value.find((episode) => episode.id === selectedEpisodeId.value) || activeEpisode.value)
const createdCount = computed(() => summaryItems.value.filter((item) => item.id > 0).length)
const lockedCount = computed(() => summaryItems.value.filter((item) => item.status === 'LOCKED').length)
const averageCompletion = computed(() => summaryItems.value.length ? Math.round(summaryItems.value.reduce((total, item) => total + item.completion, 0) / summaryItems.value.length) : 0)
const overviewStats = computed(() => [
  { label: '集数', value: episodes.value.length },
  { label: '已创建', value: `${createdCount.value} / ${episodes.value.length}` },
  { label: '已锁定', value: lockedCount.value },
  { label: '平均进度', value: `${averageCompletion.value}%` },
])
function episodeBase(stage = 'tasks') {
  return selectedEpisodeId.value ? `/projects/${projectId.value}/episodes/${selectedEpisodeId.value}/workflow/${stage}` : `/projects/${projectId.value}/workflow/${stage}`
}

function openStep(stage: string) {
  navigateTo(episodeBase(stage))
}

function applyCard(next: TaskCard) {
  card.value = next
  form.primaryGoal = next.primary_goal || ''
  form.mustInclude = next.must_include?.length ? [...next.must_include] : ['']
  form.prohibitions = next.prohibitions?.length ? [...next.prohibitions] : ['']
  form.previousSummary = next.previous_summary || ''
  form.nextGoal = next.next_goal || ''
  form.endingHook = next.ending_hook || ''
}

function localCard(): TaskCard {
  return {
    id: 1,
    episode_id: Number(selectedEpisodeId.value) || 0,
    version_no: 1,
    primary_goal: '明确本集的主要剧情目标，并为下一集保留清晰的冲突升级。',
    must_include: ['完成关键剧情推进', '保留主要角色的核心动机'],
    prohibitions: ['不得改变已建立的人物关系'],
    previous_summary: '首集，无上一集剧情。',
    next_goal: '为下一集的主要冲突和角色选择留下行动线索。',
    ending_hook: '在结尾留下一个必须立即解决的新问题。',
    status: 'DRAFT',
    completion: 100,
    locked_at: null,
    updated_at: null,
  }
}

async function loadSummary() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) {
    summaryItems.value = episodes.value.map((episode, index) => ({ id: index === 0 ? 1 : 0, episode_id: Number(episode.id) || index + 1, episode_no: episode.episodeNo, title: episode.title, status: index === 0 ? 'DRAFT' : null, completion: index === 0 ? 100 : 0, updated_at: null }))
    return
  }
  try {
    const response = await http.get<{ list: SummaryItem[]; total: number; created: number; locked: number }>(`/projects/${id}/task-cards`)
    summaryItems.value = response.list || []
  } catch {
    summaryItems.value = episodes.value.map((episode, index) => ({ id: index === 0 ? 1 : 0, episode_id: Number(episode.id) || index + 1, episode_no: episode.episodeNo, title: episode.title, status: index === 0 ? 'DRAFT' : null, completion: index === 0 ? 100 : 0, updated_at: null }))
  }
}

async function loadCard() {
  const id = Number(projectId.value)
  const episode = Number(selectedEpisodeId.value)
  if (!Number.isInteger(id) || id <= 0 || !Number.isInteger(episode) || episode <= 0) {
    applyCard(localCard())
    history.value = []
    return
  }
  loading.value = true
  try {
    const response = await http.get<{ card: TaskCard; history_count: number }>(`/projects/${id}/episodes/${episode}/task-card`)
    applyCard(response.card)
    await loadHistory()
  } catch (error) {
    applyCard(localCard())
    history.value = []
    message.warning(error instanceof Error ? `${error.message}，当前使用本地草稿` : '任务卡接口不可用，当前使用本地草稿')
  } finally {
    loading.value = false
  }
}

async function loadHistory() {
  const id = Number(projectId.value)
  const episode = Number(selectedEpisodeId.value)
  if (!Number.isInteger(id) || !Number.isInteger(episode) || episode <= 0) return
  try {
    const response = await http.get<{ list: Revision[] }>(`/projects/${id}/episodes/${episode}/task-card/history`)
    history.value = response.list || []
  } catch {
    history.value = []
  }
}

async function selectEpisode(id: string) {
  selectedEpisodeId.value = id
  switchEpisode(id)
  await navigateTo(episodeBase())
  await loadCard()
  await loadSummary()
}

function addItem(items: string[]) { items.push('') }
function removeItem(items: string[], index: number) { if (items.length > 1) items.splice(index, 1) }

async function saveCard() {
  const id = Number(projectId.value)
  const episode = Number(selectedEpisodeId.value)
  if (!card.value || isLocked.value || saving.value) return
  if (!Number.isInteger(id) || !Number.isInteger(episode) || episode <= 0) {
    card.value = {...card.value, primary_goal: form.primaryGoal, must_include: form.mustInclude.filter((item) => item.trim()), prohibitions: form.prohibitions.filter((item) => item.trim()), previous_summary: form.previousSummary, next_goal: form.nextGoal, ending_hook: form.endingHook, completion: localCompletion()}
    message.success('本地任务卡已保存')
    return
  }
  saving.value = true
  try {
    await http.put(`/projects/${id}/episodes/${episode}/task-card`, {
      primary_goal: form.primaryGoal,
      must_include: form.mustInclude.filter((item) => item.trim()),
      prohibitions: form.prohibitions.filter((item) => item.trim()),
      previous_summary: form.previousSummary,
      next_goal: form.nextGoal,
      ending_hook: form.endingHook,
    }, { payloadMode: 'json', headers: { 'Content-Type': 'application/json' } })
    await Promise.all([loadCard(), loadSummary()])
    message.success('任务卡已保存')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '任务卡保存失败')
  } finally {
    saving.value = false
  }
}

async function lockCard() {
  const id = Number(projectId.value)
  const episode = Number(selectedEpisodeId.value)
  if (!card.value) return
  if (!Number.isInteger(id) || id <= 0 || !Number.isInteger(episode) || episode <= 0) {
    card.value = {...card.value, status: 'LOCKED'}
    message.success('本地任务卡已锁定')
    return
  }
  try {
    await http.post(`/projects/${id}/episodes/${episode}/task-card/lock`)
    await Promise.all([loadCard(), loadSummary()])
    message.success('任务卡已锁定')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '任务卡锁定失败')
  }
}

async function unlockCard() {
  const id = Number(projectId.value)
  const episode = Number(selectedEpisodeId.value)
  if (!card.value) return
  if (!Number.isInteger(id) || id <= 0 || !Number.isInteger(episode) || episode <= 0) {
    if (card.value) card.value = {...card.value, status: 'DRAFT'}
    message.success('本地任务卡已解锁')
    return
  }
  try {
    await http.post(`/projects/${id}/episodes/${episode}/task-card/unlock`)
    await Promise.all([loadCard(), loadSummary()])
    message.success('任务卡已解锁')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '任务卡解锁失败')
  }
}

function useRevision(revision: Revision) {
  if (isLocked.value) return
  form.primaryGoal = revision.primary_goal || ''
  form.mustInclude = revision.must_include?.length ? [...revision.must_include] : ['']
  form.prohibitions = revision.prohibitions?.length ? [...revision.prohibitions] : ['']
  form.previousSummary = revision.previous_summary || ''
  form.nextGoal = revision.next_goal || ''
  form.endingHook = revision.ending_hook || ''
  showHistory.value = false
  message.info(`已载入历史版本 v${revision.version_no}，保存后生效`)
}

function episodeStatus(id: string) {
  return summaryItems.value.find((item) => String(item.episode_id) === id)?.status === 'LOCKED' ? '已锁定' : '任务卡草稿'
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

function localCompletion() {
  return Math.round([form.primaryGoal, form.mustInclude.some((item) => item.trim()), form.prohibitions.some((item) => item.trim()), form.previousSummary, form.nextGoal, form.endingHook].filter((item) => typeof item === 'boolean' ? item : Boolean(item && String(item).trim())).length * 100 / 6)
}

onMounted(async () => {
  try { await loadEpisodes(projectId.value) } catch { /* local fallback */ }
  if (!selectedEpisodeId.value && episodes.value[0]) selectedEpisodeId.value = episodes.value[0].id
  if (selectedEpisodeId.value && !episodes.value.some((episode) => episode.id === selectedEpisodeId.value)) selectedEpisodeId.value = episodes.value[0]?.id || ''
  await loadCard()
  await loadSummary()
})

watch(() => route.params.episodeId, (next) => {
  if (next && String(next) !== selectedEpisodeId.value) {
    selectedEpisodeId.value = String(next)
    loadCard().then(loadSummary)
  }
})

watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) {
    selectedEpisodeId.value = ''
    loadEpisodes(String(next)).then(() => {
      selectedEpisodeId.value = episodes.value[0]?.id || ''
      return loadCard().then(loadSummary)
    }).catch(() => undefined)
  }
})
</script>

<style scoped>
.task-page {
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}
</style>
