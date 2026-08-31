<template>
  <main class="script-page min-h-[calc(100vh-65px)] bg-[#f5f6fa] px-4 pb-24 pt-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-6 border-b border-[#e5e8ef] pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">01 / 07 · EPISODE WORKFLOW</p>
          <h1 class="mt-5 text-[31px] font-medium leading-[1.18]">剧本导入</h1>
          <p class="mt-3 max-w-[620px] text-[13px] leading-6 text-[#8a94aa]">将完整剧本或小说导入工作区，识别场次、角色、对白与制作资产。</p>
        </div>
        <div v-if="activeEpisode" class="rounded-[10px] border border-[#dedaff] bg-[#f4f2ff] px-3 py-2 text-xs text-[#5d52dd]">当前剧集：<strong>{{ activeEpisode.title }}</strong></div>
      </header>

      <ProductionSteps active="script" :show-episode="false" />

      <section class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        <n-card :content-style="{ padding: '0 24px 24px' }" class="!rounded-[17px] !border-[#dfe3eb]">
          <div class="flex min-h-[108px] flex-col items-start justify-between gap-4 border-b border-[#e8eaf0] px-1 py-5 sm:flex-row sm:items-center sm:px-2">
            <div>
              <h2 id="import-title" class="m-0 text-lg font-medium">导入剧本正文</h2>
              <p class="mt-2 text-xs text-[#8994a8]">粘贴全文或上传文本文件，解析后会同步更新当前项目结构。</p>
            </div>
            <label class="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-[9px] border border-[#d8d2ff] px-3 text-xs font-medium text-[#5d52ec] transition-colors hover:bg-[#f4f2ff]">
              <span aria-hidden="true" class="text-sm">↥</span> 上传文件
              <input ref="fileInput" type="file" accept=".txt,.docx" class="sr-only" @change="onFileChange" />
            </label>
          </div>

          <div
            class="script-dropzone relative mt-5 overflow-hidden rounded-[12px] border border-dashed border-[#d9dce3] bg-[#fbfcfe] transition-colors"
            :class="isDragging ? 'border-[#6654ee] bg-[#f4f2ff]' : ''"
            @dragenter.prevent="isDragging = true"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="onDrop"
          >
            <div class="flex items-center justify-between border-b border-[#e8eaf0] px-4 py-2.5">
              <span class="text-[11px] font-medium text-[#687388]">剧本正文</span>
              <span class="text-[11px] text-[#929bad]">{{ wordCount }} 字</span>
            </div>
            <textarea
              v-model="scriptText"
              class="block min-h-[330px] w-full resize-y bg-transparent px-4 py-3 text-sm leading-7 text-[#30394a] outline-none placeholder:text-[#a1a8b5]"
              placeholder="在此粘贴完整剧本/小说内容...\n\n支持一次性粘贴全部内容（多集），AI 将自动识别章节标记并拆分。"
              aria-label="剧本正文"
              @input="parsed = false"
            ></textarea>
          </div>

          <div class="mt-4 border-b border-[#e8eaf0] pb-5">
            <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <n-form-item label="解析提供商" class="!mb-0">
                <n-select v-model:value="selectedProviderId" :disabled="!providerConfigs.length" :options="providerOptions" placeholder="选择提供商" />
              </n-form-item>
              <div v-if="parseError" class="rounded-[9px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d] sm:col-span-2" role="alert">
                <strong class="block font-medium">解析失败：{{ parseError.message }}</strong>
                <div class="mt-1.5 grid grid-cols-1 gap-1 text-[11px] text-[#8e6262] sm:grid-cols-2">
                  <span>错误代码：{{ parseError.error_code ?? '未返回' }}</span>
                  <span>服务提供商：{{ parseError.provider || '未返回' }}</span>
                  <span class="sm:col-span-2">请求 ID：{{ parseError.request_id || '未返回' }}</span>
                </div>
                <pre v-if="parseError.response_content" class="mt-2 max-h-32 overflow-auto whitespace-pre-wrap break-words rounded-md bg-white px-2 py-1.5 text-[10px] leading-4 text-[#765858]">{{ parseError.response_content }}</pre>
              </div>
              <n-form-item label="解析模型" class="!mb-0">
                <n-select v-if="modelOptions.length" v-model:value="selectedModelId" :options="modelOptions" filterable placeholder="选择模型 ID" />
                <n-input v-else v-model:value="selectedModelId" placeholder="请先获取模型列表" readonly />
                <n-button :disabled="!selectedProvider" :loading="fetchingModels" attr-type="button" class="!mt-2" size="small" @click="fetchModels">获取模型</n-button>
              </n-form-item>
            </div>
            <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <span class="text-xs text-[#929bad]">支持 .txt 文本文件</span>
              <button type="button" class="inline-flex h-10 items-center justify-center gap-2 rounded-[9px] bg-[#6654ee] px-4 text-sm font-medium text-white transition-colors hover:bg-[#5848d6] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!scriptText.trim() || parsing || !selectedProviderId || !selectedModelId" @click="parseScript">
              <span aria-hidden="true" class="text-base">✦</span>{{ parsing ? '解析中...' : '开始解析' }}
              </button>
            </div>
          </div>

          <div class="pt-5" aria-labelledby="generate-title">
            <div class="flex items-center justify-between gap-3">
              <div>
                <h2 id="generate-title" class="m-0 text-sm font-semibold">AI 生成剧本</h2>
                <p class="mt-1 text-xs text-[#929bad]">从一句话创意开始，快速生成可编辑初稿。</p>
              </div>
              <span class="rounded-md bg-[#f0eeff] px-2 py-1 text-[10px] font-medium text-[#5d52ec]">智能创作</span>
            </div>
            <div class="mt-4 flex flex-col gap-3 sm:flex-row">
              <input v-model="generationPrompt" class="block h-10 min-w-0 flex-1 rounded-[9px] border border-[#dfe2e8] bg-white px-3 text-sm text-[#30394a] outline-none transition-shadow placeholder:text-[#a1a8b5] focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eceaff]" placeholder="如：穿越回古代的爱情故事" aria-label="AI 剧本创作描述" @keyup.enter="generateScript" />
              <button type="button" class="inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-[9px] border border-[#d8d2ff] px-4 text-sm font-medium text-[#5d52ec] transition-colors hover:bg-[#f4f2ff] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!generationPrompt.trim() || generating" @click="generateScript"><span aria-hidden="true">✦</span>{{ generating ? '生成中...' : 'AI 写剧本' }}</button>
            </div>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <span class="mr-1 text-xs text-[#929bad]">风格</span>
              <button v-for="style in styles" :key="style" type="button" class="rounded-full border px-3 py-1 text-[11px] transition-colors" :class="selectedStyle === style ? 'border-[#776ce9] bg-[#f0eeff] text-[#5c50dc]' : 'border-[#dfe2e8] text-[#646d7e] hover:bg-[#f7f7fa]'" :aria-pressed="selectedStyle === style" @click="selectedStyle = selectedStyle === style ? '' : style">{{ style }}</button>
            </div>
            <p v-if="generatedNotice" class="mt-3 text-xs text-[#687388]" role="status">{{ generatedNotice }}</p>
          </div>

          <section v-if="parsed" class="mt-5 rounded-[11px] border border-[#dfe3eb] bg-[#fbfbfd] p-4" aria-labelledby="parsed-title">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div><h2 id="parsed-title" class="m-0 text-sm font-semibold">解析预览</h2><p class="mt-1 text-xs text-[#929bad]">结构化结果已就绪，可继续维护资产。</p></div>
              <span class="rounded-md bg-[#e9f7f1] px-2 py-1 text-[10px] font-medium text-[#278d68]">已解析</span>
            </div>
            <div class="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div v-for="stat in parsedStats" :key="stat.label" class="rounded-[9px] bg-white px-3 py-2.5"><strong class="block text-lg font-semibold text-[#30394a]">{{ stat.value }}</strong><span class="mt-0.5 block text-[11px] text-[#8b93a2]">{{ stat.label }}</span></div>
            </div>
            <div v-if="analysisResult" class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div class="rounded-[9px] bg-white p-3">
                <span class="text-[11px] text-[#929bad]">人物资产</span>
                <div v-if="analysisResult.characters?.length" class="mt-2 flex flex-wrap gap-1.5">
                  <span v-for="item in analysisResult.characters" :key="item.id || item.name" class="rounded-md bg-[#eceaff] px-2 py-1 text-[11px] text-[#5d52dd]">{{ item.name }}</span>
                </div>
                <p v-else class="mt-2 text-xs text-[#a1a8b5]">未识别到人物</p>
              </div>
              <div class="rounded-[9px] bg-white p-3">
                <span class="text-[11px] text-[#929bad]">场景资产</span>
                <div v-if="analysisResult.scenes?.length" class="mt-2 space-y-1.5">
                  <p v-for="item in analysisResult.scenes.slice(0, 4)" :key="item.id || item.name" class="m-0 truncate text-xs text-[#4f596d]">{{ item.name || item.location || '未命名场景' }}</p>
                </div>
                <p v-else class="mt-2 text-xs text-[#a1a8b5]">未识别到场景</p>
              </div>
              <div class="rounded-[9px] bg-white p-3">
                <span class="text-[11px] text-[#929bad]">道具资产</span>
                <div v-if="analysisResult.props?.length" class="mt-2 space-y-1.5">
                  <p v-for="item in analysisResult.props.slice(0, 4)" :key="item.id || item.name" class="m-0 truncate text-xs text-[#4f596d]">{{ item.name }}</p>
                </div>
                <p v-else class="mt-2 text-xs text-[#a1a8b5]">未识别到道具</p>
              </div>
            </div>
            <p v-if="analysisResult?.overall_summary" class="mt-3 text-xs leading-5 text-[#687388]">{{ analysisResult.overall_summary }}</p>
          </section>
        </n-card>

        <aside class="script-assistant rounded-[17px] border border-[#dfe3eb] bg-white">
          <div class="border-b border-[#e8eaf0] px-5 py-[18px]"><div class="flex items-center gap-2"><span class="text-xl text-[#5d51ef]" aria-hidden="true">✦</span><div><h2 class="m-0 text-[13px] font-semibold">剧本导入助手</h2><p class="mt-1.5 text-[11px] text-[#8e98aa]">在线 · 已读取当前节点</p></div></div></div>
          <div class="space-y-4 px-5 py-5">
            <div class="rounded-[11px] bg-[#f7f7fb] px-3 py-3"><span class="text-[11px] text-[#929bad]">当前上下文</span><strong class="mt-2 block text-[13px] font-semibold text-[#273148]">{{ activeEpisode?.title || '项目剧本' }}</strong><div class="mt-2 flex flex-wrap gap-1.5"><span class="rounded-md bg-[#eceaff] px-2 py-1 text-[10px] text-[#6157e9]">正文结构</span><span class="rounded-md bg-[#eceaff] px-2 py-1 text-[10px] text-[#6157e9]">历史版本</span></div></div>
            <div class="flex gap-2.5 rounded-[11px] bg-[#f5f6f8] px-3 py-3"><span class="shrink-0 text-[#5e52ee]" aria-hidden="true">✦</span><p class="m-0 text-xs leading-[1.65] text-[#687388]">先导入剧本正文，再运行解析。解析完成后可以在资产管理中检查角色、场景和道具。</p></div>
            <div class="border-t border-[#e8eaf0] pt-4"><span class="text-[11px] text-[#8994a8]">解析状态</span><div class="mt-3 flex items-center justify-between text-xs"><span class="text-[#687388]">当前版本</span><strong :class="parsed ? 'text-[#278d68]' : 'text-[#d08a2d]'">{{ parsed ? '已解析' : '待解析' }}</strong></div><div class="mt-3 h-1.5 overflow-hidden rounded-full bg-[#ececf4]"><div class="h-full rounded-full bg-[#6654ee] transition-all" :style="{ width: parsed ? '100%' : scriptText.trim() ? '25%' : '0%' }"></div></div></div>
          </div>
        </aside>
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
  analysis?: { status: string; provider_config_id: number | null; model: string | null; analyzed_at: string | null; result: ScriptAnalysis | null }
}

interface ScriptAnalysisItem { id?: string; name?: string; location?: string; description?: string }
interface ScriptAnalysis { title?: string; genre?: string; overall_summary?: string; characters?: ScriptAnalysisItem[]; scenes?: ScriptAnalysisItem[]; props?: ScriptAnalysisItem[]; costumes?: ScriptAnalysisItem[] }

interface ProviderConfig {
  id: number
  provider: string
  model_id: string
  base_url: string
  enabled: boolean
}

interface ParseError {
  message: string
  error_code?: number | null
  response_content?: string | null
  provider?: string
  request_id?: string
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
const providerConfigs = ref<ProviderConfig[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedModelId = ref('')
const modelOptions = ref<{ label: string; value: string }[]>([])
const fetchingModels = ref(false)
const scriptStats = ref<ScriptStats>({
  scene_count: 0,
  character_count: 0,
  dialogue_count: 0,
  estimated_duration_seconds: 0,
  structure_score: null,
})
const analysisResult = ref<ScriptAnalysis | null>(null)
const parseError = ref<ParseError | null>(null)

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
const providerOptions = computed(() => providerConfigs.value.map((config) => ({ label: config.provider, value: config.id })))
const selectedProvider = computed(() => providerConfigs.value.find((config) => config.id === selectedProviderId.value))
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
  analysisResult.value = data.analysis?.result || null
  parsed.value = data.analysis?.status === 'COMPLETED' || Boolean(data.scenes?.length || data.characters?.length || data.dialogues?.length)
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

async function loadProviderConfigs() {
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    providerConfigs.value = response.list.filter((config) => config.enabled)
    if (providerConfigs.value.length === 1) {
      selectedProviderId.value = providerConfigs.value[0].id
      selectedModelId.value = providerConfigs.value[0].model_id || ''
    }
  } catch {
    providerConfigs.value = []
  }
}

async function fetchModels() {
  if (!selectedProvider.value) return
  const formData = new FormData()
  formData.append('base_url', selectedProvider.value.base_url)
  formData.append('config_id', String(selectedProvider.value.id))

  fetchingModels.value = true
  try {
    const response = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
    modelOptions.value = response.list.map((model) => ({ label: model.id, value: model.id }))
    if (!modelOptions.value.length) message.warning('该提供商未返回可用模型')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '获取模型列表失败')
  } finally {
    fetchingModels.value = false
  }
}

async function saveScript() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) throw new Error('当前项目未保存到服务器，请先创建项目')
  const data = await http.put<{ content: string; stats: ScriptStats }>(`/projects/${id}/script`, scriptText.value, {
    payloadMode: 'json',
    params: episodeQuery.value,
    headers: { 'Content-Type': 'text/plain; charset=UTF-8' },
  })
  scriptStats.value = data.stats || scriptStats.value
  scriptText.value = data.content || scriptText.value
}

async function parseScript() {
  if (!scriptText.value.trim()) {
    message.warning('请先粘贴或上传剧本内容')
    return
  }
  if (!selectedProviderId.value || !selectedModelId.value) {
    message.warning('请选择解析提供商和模型')
    return
  }
  parsing.value = true
  parseError.value = null
  generatedNotice.value = ''
  try {
    await saveScript()
  } catch (error) {
    parsing.value = false
    parseError.value = extractParseError(error, '剧本保存失败，无法开始解析')
    message.error(parseError.value.message)
    return
  }
  try {
    const id = Number(projectId.value)
    const response = await http.post<{ document_id: number; version_id: number; analysis_status: string; result: ScriptAnalysis | null }>(`/projects/${id}/script/parse`, {
      provider_config_id: selectedProviderId.value,
      model: selectedModelId.value,
      ...(episodeQuery.value || {}),
    })
    analysisResult.value = response.result || null
    await Promise.all([loadEpisodes(projectId.value), loadLibrary(projectId.value)])
    parsed.value = response.analysis_status === 'COMPLETED'
  } catch (error) {
    parsed.value = false
    parseError.value = extractParseError(error, '剧本解析失败')
    message.error(parseError.value.message)
    parsing.value = false
    return
  }
  parsing.value = false
  message.success('剧本解析完成，结构化结果已保存')
}

function extractParseError(error: unknown, fallback: string): ParseError {
  const value = error as { message?: string; data?: { data?: { error?: ParseError }; message?: string } }
  const structured = value.data?.data?.error
  return structured ? { ...structured, message: value.data?.message || value.message || fallback } : { message: value.data?.message || value.message || fallback }
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
  await Promise.all([loadScript(), loadProviderConfigs()])
})

watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) loadScript()
})

watch(episodeId, (next, previous) => {
  if (next && next !== previous) loadScript()
})

watch(selectedProviderId, (next, previous) => {
  if (previous !== undefined && previous !== null && next !== previous) {
    selectedModelId.value = ''
    modelOptions.value = []
  }
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
