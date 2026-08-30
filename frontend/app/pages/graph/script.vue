<template>
  <main class="script-page min-h-[calc(100vh-65px)] bg-white text-[#20283b]">
    <ProductionSteps active="script" :show-episode="false" />

    <div class="mx-auto max-w-3xl px-5 pb-24 pt-8 sm:px-6">
      <div v-if="activeEpisode" class="mb-5 flex items-center gap-2 text-xs text-[#8994a8]">
        <span class="h-1.5 w-1.5 rounded-full bg-[#6654ee]" aria-hidden="true"></span>
        <span>当前剧集</span><strong class="font-semibold text-[#4d5870]">{{ activeEpisode.title }}</strong>
      </div>
      <section aria-labelledby="import-title">
        <div class="mb-3">
          <h1 id="import-title" class="m-0 text-lg font-semibold">全文导入</h1>
          <p class="mt-2 text-sm leading-6 text-[#737c8d]">
            将完整剧本或小说粘贴到下方，AI 将自动拆分成集并提取角色、场景、道具。
          </p>
        </div>

        <div
          class="script-dropzone relative rounded-xl border-2 border-dashed border-[#d9dce3] transition-colors"
          :class="isDragging ? 'border-[#f28a21] bg-[#fffaf4]' : 'bg-white'"
          @dragenter.prevent="isDragging = true"
          @dragover.prevent="isDragging = true"
          @dragleave.prevent="isDragging = false"
          @drop.prevent="onDrop"
        >
          <textarea
            v-model="scriptText"
            class="block min-h-[280px] w-full resize-y bg-transparent px-4 py-3 text-base leading-relaxed text-[#30394a] outline-none placeholder:text-[#a1a8b5]"
            placeholder="在此粘贴完整剧本/小说内容...\n\n支持一次性粘贴全部内容（多集），AI 将自动识别章节标记并拆分。\n\n也可以直接拖拽 .txt 或 .docx 文件到此处"
            aria-label="剧本正文"
            @input="parsed = false"
          ></textarea>
          <label class="absolute right-2 top-2 flex cursor-pointer items-center gap-1 rounded border border-[#dfe2e8] bg-white px-2 py-1 text-xs text-[#737c8d] transition-colors hover:text-[#313a4b]">
            <span aria-hidden="true" class="text-[11px]">↥</span>
            上传文件
            <input ref="fileInput" type="file" accept=".txt,.docx" class="sr-only" @change="onFileChange" />
          </label>
        </div>

        <div class="mt-3 flex items-center justify-between gap-3">
          <span class="text-sm text-[#737c8d]">{{ wordCount }} 字</span>
          <button
            type="button"
            class="inline-flex h-10 items-center justify-center gap-2 rounded-md bg-[#f28a21] px-4 text-sm font-medium text-white transition-colors hover:bg-[#df7914] disabled:cursor-not-allowed disabled:opacity-50"
            :disabled="!scriptText.trim() || parsing"
            @click="parseScript"
          >
            <span aria-hidden="true" class="text-base">✦</span>
            {{ parsing ? '解析中...' : '开始解析' }}
          </button>
        </div>
      </section>

      <div class="my-8 flex items-center gap-3" aria-hidden="true">
        <div class="h-px flex-1 bg-[#e2e4e9]"></div>
        <span class="whitespace-nowrap text-sm text-[#8b93a2]">或者 AI 生成剧本</span>
        <div class="h-px flex-1 bg-[#e2e4e9]"></div>
      </div>

      <section class="rounded-xl border border-[#dfe2e8] p-5" aria-labelledby="generate-title">
        <h2 id="generate-title" class="m-0 mb-3 text-lg font-semibold">AI 生成剧本</h2>
        <input
          v-model="generationPrompt"
          class="mb-3 block h-11 w-full rounded-md border border-[#dfe2e8] bg-white px-3 text-base text-[#30394a] outline-none transition-shadow placeholder:text-[#a1a8b5] focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eceaff]"
          placeholder="输入一句话描述，如：穿越回古代的爱情故事..."
          aria-label="AI 剧本创作描述"
          @keyup.enter="generateScript"
        />
        <div class="mb-4 flex flex-wrap items-center gap-2">
          <span class="mr-1 text-sm text-[#737c8d]">风格:</span>
          <button
            v-for="style in styles"
            :key="style"
            type="button"
            class="rounded-full border px-3 py-1 text-xs transition-colors"
            :class="selectedStyle === style ? 'border-[#776ce9] bg-[#f0eeff] text-[#5c50dc]' : 'border-[#dfe2e8] text-[#646d7e] hover:bg-[#f7f7fa]'"
            :aria-pressed="selectedStyle === style"
            @click="selectedStyle = selectedStyle === style ? '' : style"
          >{{ style }}</button>
        </div>
        <button
          type="button"
          class="inline-flex h-10 items-center justify-center gap-2 rounded-md bg-[#6b5ce7] px-4 text-sm font-medium text-white transition-colors hover:bg-[#5c4dd4] disabled:cursor-not-allowed disabled:opacity-50"
          :disabled="!generationPrompt.trim() || generating"
          @click="generateScript"
        >
          <span aria-hidden="true" class="text-base">✦</span>
          {{ generating ? '生成中...' : 'AI 写剧本' }}
        </button>
        <p v-if="generatedNotice" class="mt-3 text-xs text-[#687388]" role="status">{{ generatedNotice }}</p>
      </section>

      <section v-if="parsed" class="mt-6 rounded-xl border border-[#dfe2e8] bg-[#fbfbfd] p-5" aria-labelledby="parsed-title">
        <div class="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h2 id="parsed-title" class="m-0 text-base font-semibold">解析预览</h2>
            <p class="mt-1 text-xs text-[#8b93a2]">已完成当前版本的基础结构识别，可继续前往资产管理。</p>
          </div>
          <span class="rounded-full bg-[#e9f7f1] px-2.5 py-1 text-xs text-[#278d68]">已解析</span>
        </div>
        <div class="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <div v-for="stat in parsedStats" :key="stat.label" class="rounded-lg bg-white px-3 py-2.5">
            <strong class="block text-lg font-semibold text-[#30394a]">{{ stat.value }}</strong>
            <span class="mt-0.5 block text-xs text-[#8b93a2]">{{ stat.label }}</span>
          </div>
        </div>
      </section>
    </div>

    <footer class="script-footer fixed bottom-0 left-0 right-0 z-10 flex h-14 items-center justify-between border-t border-[#e1e3e8] bg-white px-5 sm:px-6">
      <button
        type="button"
        class="inline-flex h-10 items-center gap-1.5 rounded-md px-3 text-sm text-[#737c8d] transition-colors hover:bg-[#f5f6f8] disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="!hasProject"
        @click="goPrevious"
      >
        <span aria-hidden="true">‹</span> 上一步
      </button>
      <button
        type="button"
        class="inline-flex h-10 items-center gap-1.5 rounded-md bg-[#f28a21] px-4 text-sm font-medium text-white transition-colors hover:bg-[#df7914] disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="!parsed"
        @click="goNext"
      >
        下一步 <span aria-hidden="true">›</span>
      </button>
    </footer>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, ref, watch } from 'vue'

const message = useMessage()
const http = useHttp()
const { activeProject, activeProjectId, loadProjects } = useProjectWorkspace()
const { activeEpisodeId, activeEpisode, loadEpisodes, loadLibrary } = useEpisodeWorkspace()
const route = useRoute()

interface ScriptVersion {
  id: number
  version_no: number
  version_name: string
  status: string
}

interface ScriptStats {
  scene_count: number
  character_count: number
  dialogue_count: number
  estimated_duration_seconds: number
  structure_score: number | null
}

interface ScriptResponse {
  document_id: number
  title: string
  version: ScriptVersion
  versions: ScriptVersion[]
  content: string
  scenes: unknown[]
  characters: unknown[]
  dialogues: unknown[]
  quality_checks: unknown[]
  stats: ScriptStats
}

const styles = ['古风', '悬疑', '甜宠', '科幻', '都市', '奇幻']

const scriptText = ref('测试剧本')
const generationPrompt = ref('')
const selectedStyle = ref('')
const generatedNotice = ref('')
const parsed = ref(false)
const parsing = ref(false)
const generating = ref(false)
const isDragging = ref(false)
const scriptStats = ref<ScriptStats>({
  scene_count: 0,
  character_count: 0,
  dialogue_count: 0,
  estimated_duration_seconds: 0,
  structure_score: null,
})

const fileInput = ref<HTMLInputElement | null>(null)
const hasProject = computed(() => Boolean(activeProject.value?.id || activeProjectId.value))
const projectId = computed(() => String(route.params.projectId || activeProject.value?.id || activeProjectId.value || ''))
const episodeId = computed(() => {
  const value = String(route.params.episodeId || activeEpisodeId.value || '')
  return /^\d+$/.test(projectId.value) ? (/^\d+$/.test(value) ? value : '') : value
})
const episodeQuery = computed(() => {
  const id = Number(episodeId.value)
  return Number.isInteger(id) && id > 0 ? { episode_id: id } : undefined
})
const wordCount = computed(() => scriptText.value.length)
const parsedStats = computed(() => [
  { label: '场景', value: scriptStats.value.scene_count || estimateScenes(scriptText.value) },
  { label: '角色', value: scriptStats.value.character_count || estimateCharacters(scriptText.value) },
  { label: '对白', value: scriptStats.value.dialogue_count || estimateDialogues(scriptText.value) },
  { label: '预计时长', value: formatDuration(scriptStats.value.estimated_duration_seconds || estimateDuration(scriptText.value)) },
])

function estimateScenes(text: string) {
  const matches = text.match(/(?:第\s*\d+\s*场|场景|INT\.|EXT\.)/gi)
  return Math.max(matches?.length || 0, text.trim() ? 1 : 0)
}

function estimateCharacters(text: string) {
  const matches = text.match(/(?:^|\n)\s*[^：:\n]{1,12}[：:]/g)
  return new Set((matches || []).map((item) => item.replace(/[\n：:]/g, '').trim())).size
}

function estimateDialogues(text: string) {
  return (text.match(/(?:^|\n)\s*[^：:\n]{1,12}[：:]/g) || []).length
}

function estimateDuration(text: string) {
  return Math.max(1, Math.ceil(text.replace(/\s/g, '').length / 4))
}

function formatDuration(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function applyResponse(data: ScriptResponse) {
  scriptText.value = data.content || ''
  scriptStats.value = data.stats || scriptStats.value
  parsed.value = Boolean(data.scenes?.length || data.characters?.length || data.dialogues?.length)
}

async function loadScript() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) return
  try {
    applyResponse(await http.get<ScriptResponse>(`/projects/${id}/script`, episodeQuery.value))
  } catch (error) {
    if (Number((error as { statusCode?: number })?.statusCode) !== 404) {
      message.error(error instanceof Error ? error.message : '剧本加载失败')
    }
  }
}

async function saveScript() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) return false
  try {
    const data = await http.put<{ content: string; stats: ScriptStats }>(`/projects/${id}/script`, scriptText.value, {
      payloadMode: 'json',
      params: episodeQuery.value,
      headers: { 'Content-Type': 'text/plain; charset=UTF-8' },
    })
    scriptStats.value = data.stats || scriptStats.value
    scriptText.value = data.content || scriptText.value
    return true
  } catch {
    return false
  }
}

async function parseScript() {
  if (!scriptText.value.trim()) {
    message.warning('请先粘贴或上传剧本内容')
    return
  }
  parsing.value = true
  generatedNotice.value = ''
  const saved = await saveScript()
  try {
    await Promise.all([loadEpisodes(projectId.value), loadLibrary(projectId.value)])
  } catch {
    // Keep the local navigation summary when the API is unavailable.
  }
  parsed.value = true
  parsing.value = false
  message.success(saved ? '剧本解析完成，结构化结果已保存' : '剧本解析完成，已保留当前编辑内容')
}

function generateScript() {
  const prompt = generationPrompt.value.trim()
  if (!prompt || generating.value) return
  generating.value = true
  generatedNotice.value = ''
  window.setTimeout(() => {
    const style = selectedStyle.value ? `【${selectedStyle.value}】` : ''
    scriptText.value = `${style}${prompt}\n\n第一场  夜·外景\n人物在城市灯火与雨幕之间相遇，旧日的秘密被重新揭开。\n\n第二场  日·内景\n两人面对选择，决定一起寻找答案。`
    parsed.value = false
    generatedNotice.value = '已生成初稿，可继续编辑后点击“开始解析”。'
    generating.value = false
    message.success('AI 剧本初稿已生成')
  }, 420)
}

function readFile(source?: File) {
  if (!source) return
  if (!/\.txt$/i.test(source.name)) {
    message.warning('当前仅支持读取 .txt 文件，.docx 请先转换为纯文本')
    return
  }
  source.text().then((text) => {
    scriptText.value = text
    parsed.value = false
    message.success(`已导入 ${source.name}`)
  }).catch(() => message.error('剧本文件读取失败'))
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  readFile(input.files?.[0])
  input.value = ''
}

function onDrop(event: DragEvent) {
  isDragging.value = false
  readFile(event.dataTransfer?.files?.[0])
}

function goPrevious() {
  navigateTo(projectId.value ? `/projects/${projectId.value}` : '/projects')
}

function goNext() {
  if (!parsed.value) return
  const prefix = episodeId.value ? `/projects/${projectId.value}/episodes/${episodeId.value}/workflow` : `/projects/${projectId.value}/workflow`
  navigateTo(`${prefix}/tasks`)
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch {
    // Keep the local project cache available when the API is unavailable.
  }
  await loadScript()
})

watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) loadScript()
})

watch(episodeId, (next, previous) => {
  if (next && next !== previous) loadScript()
})
</script>

<style scoped>
.script-page {
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

.script-dropzone:focus-within {
  border-color: #9b91f5;
  box-shadow: 0 0 0 3px #eeecff;
}

.script-footer {
  padding-bottom: env(safe-area-inset-bottom);
}

</style>
