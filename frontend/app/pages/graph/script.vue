<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[27px] text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">01 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div>
            <h1 class="m-0 text-[31px] font-medium leading-[1.18]">剧本输入</h1>
            <p class="mt-[11px] text-[13px] text-[#8a94aa]">
              故事结构、场次、人物与对白，所有修改均纳入版本记录和质量门禁。</p>
          </div>
          <div class="flex w-full items-center gap-2 sm:w-auto">
            <n-button :disabled="isLocked || loading" :loading="saving" class="!h-[42px] !rounded-[10px]" secondary
                      @click="saveDraft">保存草稿
            </n-button>
            <n-button aria-label="发布项目"
                      class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost/>
          </div>
        </div>
      </header>

      <ProductionSteps active="script"/>

      <section class="grid min-h-[838px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="editor-card !rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div>
              <h2 class="m-0 text-lg font-medium">结构化剧本编辑器</h2>
              <p class="mt-[9px] text-xs text-[#8994a8]">支持正文导入、分场拆解、人物对白提取与版本审批</p>
            </div>
            <n-upload :show-file-list="false" accept=".txt,.doc,.docx" @change="onFileChange">
              <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost>导入剧本</n-button>
            </n-upload>
          </div>

          <n-tabs v-model:value="selectedTab" animated class="script-tabs" justify-content="space-between" type="line">
            <n-tab-pane v-for="tab in editorTabs" :key="tab" :name="tab" :tab="tab"/>
          </n-tabs>

          <div
              class="flex min-h-[71px] flex-col items-start justify-between gap-3 py-3 text-xs text-[#7d879b] sm:flex-row sm:items-center sm:py-0">
            <span>版本 {{ versionLabel }} · {{ isLocked ? '已锁定' : '编辑修订稿' }}</span>
            <n-space :size="6">
              <n-button :disabled="isLocked || loading" :loading="formatting" class="!h-10 !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]"
                        ghost @click="aiFormat">✦ AI 结构化
              </n-button>
              <n-button :disabled="isLocked || loading" :loading="locking" class="!h-10 !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]"
                        ghost @click="lockVersion">锁定版本
              </n-button>
            </n-space>
          </div>

          <n-input
              v-model:value="scriptText"
              :autosize="{ minRows: 10, maxRows: 15 }"
              :disabled="isLocked || loading"
              aria-label="剧本文本"
              class="script-editor"
              placeholder="输入或导入剧本文本"
              type="textarea"
          />

          <div
              class="flex min-h-[69px] flex-wrap items-center gap-x-[26px] gap-y-3 border-b border-[#e8eaf0] py-3 sm:py-0">
            <div v-for="stat in stats" :key="stat.label" class="flex items-baseline gap-2">
              <strong class="text-[19px] font-bold text-[#5a6477]">{{ stat.value }}</strong>
              <span class="text-xs text-[#8b96aa]">{{ stat.label }}</span>
            </div>
          </div>

          <section class="pt-[21px]">
            <h3 class="mb-[13px] text-[13px] font-medium">剧本质量门禁</h3>
            <div class="grid grid-cols-1 gap-[9px] sm:grid-cols-2">
              <n-alert
                  v-for="item in qualityItems"
                  :key="item.title"
                  :bordered="false"
                  :show-icon="false"
                  :type="item.ok ? 'success' : 'warning'"
                  class="quality-alert !min-h-[63px] !rounded-[10px]"
              >
                <template #header>{{ item.ok ? '✓' : '!' }} {{ item.title }}</template>
                <span class="text-[11px] text-[#8490a4]">{{ item.note }}</span>
              </n-alert>
            </div>
          </section>

          <section v-if="selectedTab !== '正文'" class="mt-5 border-t border-[#e8eaf0] pt-5">
            <div v-if="selectedTab === '场次表'" class="space-y-2">
              <div v-for="scene in scenes" :key="scene.id" class="rounded-lg border border-[#e8eaf0] px-3 py-2 text-xs">
                <strong>第 {{ scene.scene_no }} 场 · {{ scene.title }}</strong>
                <span class="ml-2 text-[#8994a8]">{{ scene.duration_seconds }} 秒</span>
                <p v-if="scene.content" class="mt-1 line-clamp-2 text-[#8994a8]">{{ scene.content }}</p>
              </div>
              <p v-if="!scenes.length" class="text-xs text-[#8994a8]">暂无结构化场次，请保存剧本后重试。</p>
            </div>
            <div v-else-if="selectedTab === '人物表'" class="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <div v-for="character in characters" :key="character.id"
                   class="rounded-lg border border-[#e8eaf0] px-3 py-2 text-xs">
                <strong>{{ character.name }}</strong>
                <p class="mt-1 text-[#8994a8]">{{ character.motivation || character.description || '暂无人物设定' }}</p>
              </div>
              <p v-if="!characters.length" class="text-xs text-[#8994a8]">
                暂无角色数据，请使用“角色：对白”格式补充对白。</p>
            </div>
            <div v-else-if="selectedTab === '对白表'" class="space-y-2">
              <div v-for="dialogue in dialogues" :key="dialogue.id"
                   class="rounded-lg border border-[#e8eaf0] px-3 py-2 text-xs">
                <strong>{{ characterName(dialogue.character_id) }}</strong><span
                  class="ml-2 text-[#8994a8]">{{ dialogue.content }}</span>
              </div>
              <p v-if="!dialogues.length" class="text-xs text-[#8994a8]">暂无对白数据。</p>
            </div>
            <div v-else class="space-y-2">
              <div v-for="item in versions" :key="item.id"
                   class="flex items-center justify-between rounded-lg border border-[#e8eaf0] px-3 py-2 text-xs">
                <span>{{ item.version_name }}</span>
                <n-tag :bordered="false" size="small">{{ versionStatus(item.status) }}</n-tag>
              </div>
            </div>
          </section>
        </n-card>

        <n-card :content-style="{ padding: 0, height: '100%' }"
                class="assistant-panel !rounded-[17px] !border-[#dfe3eb]">
          <div class="flex min-h-[81px] items-start gap-[13px] border-b border-[#e8eaf0] px-[21px] py-[18px]">
            <span class="text-[22px] leading-[22px] text-[#5d51ef]">✦</span>
            <div>
              <h2 class="m-0 text-[13px] font-semibold">剧本输入助手</h2>
              <p class="mt-[5px] text-[11px] text-[#8e98aa]">
                <n-badge dot type="success"/>
                <span class="ml-1">在线 · 已读取当前节点</span></p>
            </div>
          </div>

          <div class="px-[18px] py-[19px]">
            <div class="flex min-h-[90px] flex-col rounded-xl bg-[#f7f7fb] p-[13px]">
              <span class="text-[11px] text-[#929bad]">当前上下文</span>
              <strong class="mt-2 text-[13px] font-semibold text-[#273148]">{{ activeProject.name }} · {{
                  versionLabel
                }}</strong>
              <div class="mt-[5px] flex flex-wrap gap-1">
                <n-tag v-for="tag in contextTags" :key="tag" :bordered="false" class="!bg-transparent !px-0 !text-[11px] !text-[#6157e9]"
                       size="small">{{ tag }}
                </n-tag>
              </div>
            </div>
            <div class="mt-[17px] flex gap-[11px] rounded-[11px] bg-[#f5f6f8] py-3 pr-3">
              <span class="w-[21px] shrink-0 text-center text-[#5e52ee]">✦</span>
              <p class="m-0 text-xs leading-[1.65] text-[#687388]">{{ assistantMessage }}</p>
            </div>
          </div>

          <div class="mt-auto border-t border-[#e8eaf0]">
            <div class="min-h-[96px] border-b border-[#e8eaf0] px-[15px] py-[17px]">
              <span class="text-[11px] text-[#8994a8]">推荐操作</span>
              <div class="mt-3 flex flex-wrap gap-2">
                <n-button v-for="suggestion in suggestions" :key="suggestion"
                          class="!h-8 !rounded-lg !border-[#d8d2ff] !px-[10px] !text-[11px] !text-[#5d52ec]" ghost
                          @click="useSuggestion(suggestion)">{{ suggestion }} ↗
                </n-button>
              </div>
            </div>
            <form class="min-h-[132px] px-[15px] py-[14px]" @submit.prevent="sendMessage">
              <n-input v-model:value="chatInput" :bordered="false" :disabled="loading" placeholder="告诉助手你想优化什么..."
                       type="text"/>
              <div class="flex h-[72px] items-end justify-between text-[11px] text-[#a0a8b8]">
                <span>Enter 发送</span>
                <n-button aria-label="发送消息"
                          attr-type="submit" class="!h-[34px] !w-[34px] !rounded-[9px] !bg-[#b8b1ff] !p-0 !text-[22px] !text-white" quaternary>↑
                </n-button>
              </div>
            </form>
          </div>
        </n-card>
      </section>
    </div>
  </main>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref, watch} from 'vue'

const message = useMessage()
const http = useHttp()
const {activeProject, loadProjects} = useProjectWorkspace()

interface ScriptVersion {
  id: number;
  version_no: number;
  version_name: string;
  status: string;
  created_at?: string | null;
  locked_at?: string | null
}

interface ScriptScene {
  id: number;
  scene_no: number;
  title: string;
  location?: string | null;
  time_of_day?: string | null;
  duration_seconds: number;
  content?: string | null
}

interface ScriptCharacter {
  id: number;
  name: string;
  description?: string | null;
  motivation?: string | null;
  relationship?: string | null
}

interface ScriptDialogue {
  id: number;
  script_scene_id: number;
  character_id?: number | null;
  sequence_no: number;
  content: string;
  dialogue_type: string;
  duration_seconds: number
}

interface QualityCheck {
  id: number;
  rule_code: string;
  title: string;
  note?: string | null;
  passed: boolean;
  score?: number | null
}

interface ScriptStats {
  scene_count: number;
  character_count: number;
  dialogue_count: number;
  estimated_duration_seconds: number;
  structure_score: number | null
}

interface ScriptResponse {
  document_id: number;
  title: string;
  version: ScriptVersion;
  versions: ScriptVersion[];
  content: string;
  scenes: ScriptScene[];
  characters: ScriptCharacter[];
  dialogues: ScriptDialogue[];
  quality_checks: QualityCheck[];
  stats: ScriptStats
}

const editorTabs = ['正文', '场次表', '人物表', '对白表', '版本记录']
const contextTags = ['节点数据', '历史版本', '质量门禁']
const suggestions = ['拆分场次和对白', '检查前三轮钩子', '生成资产需求']

const selectedTab = ref('正文')
const scriptText = ref('雨夜，失意摄影师林夏错过末班车。她在空荡的站台收到一封没有署名的信，寄信人竟是十年前的自己。\n信里只有一句话：不要登上下一班车。远处车灯穿过雨幕，她听见车内有人喊出自己的名字……')
const chatInput = ref('')
const assistantMessage = ref('我是剧本总监助手。可以帮你拆分场次、检查人物动机、优化句子，并整理成可生产的结构化剧本。')
const version = ref<ScriptVersion | null>(null)
const versions = ref<ScriptVersion[]>([])
const scenes = ref<ScriptScene[]>([])
const characters = ref<ScriptCharacter[]>([])
const dialogues = ref<ScriptDialogue[]>([])
const qualityChecks = ref<QualityCheck[]>([])
const scriptStats = ref<ScriptStats>({
  scene_count: 0,
  character_count: 0,
  dialogue_count: 0,
  estimated_duration_seconds: 0,
  structure_score: null
})
const loading = ref(false)
const saving = ref(false)
const locking = ref(false)
const formatting = ref(false)
const isLocked = computed(() => version.value?.status === 'LOCKED' || version.value?.status === 'PUBLISHED')
const versionLabel = computed(() => version.value?.version_name || activeProject.value.version || 'v0.1')
const stats = computed(() => {
  const duration = scriptStats.value.estimated_duration_seconds
  return [
    {value: String(scriptStats.value.scene_count), label: '场戏'},
    {value: String(scriptStats.value.character_count), label: '角色'},
    {value: String(scriptStats.value.dialogue_count), label: '句对白'},
    {value: `${Math.floor(duration / 60)}:${String(duration % 60).padStart(2, '0')}`, label: '预计时长'},
    {
      value: scriptStats.value.structure_score == null ? '--' : String(scriptStats.value.structure_score),
      label: '结构评分'
    },
  ]
})
const qualityItems = computed(() => qualityChecks.value.map((item) => ({
  title: item.title,
  note: item.note || '暂无检查说明',
  ok: item.passed
})))


function applyResponse(data: ScriptResponse) {
  version.value = data.version
  versions.value = data.versions || []
  scriptText.value = data.content || ''
  scenes.value = data.scenes || []
  characters.value = data.characters || []
  dialogues.value = data.dialogues || []
  qualityChecks.value = data.quality_checks || []
  scriptStats.value = data.stats || scriptStats.value
}

async function loadScript() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  loading.value = true
  try {
    const data = await http.get<ScriptResponse>(`/projects/${projectId}/script`)
    applyResponse(data)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '剧本加载失败')
  } finally {
    loading.value = false
  }
}

async function saveDraft(): Promise<boolean> {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) {
    message.warning('当前项目尚未从服务器加载，无法保存剧本')
    return false
  }
  saving.value = true
  try {
    const data = await http.put<{
      document_id: number;
      title: string;
      version: ScriptVersion;
      content: string;
      stats: ScriptStats
    }>(`/projects/${projectId}/script`, scriptText.value, {
      payloadMode: 'json',
      headers: {'Content-Type': 'text/plain; charset=UTF-8'}
    })
    version.value = data.version
    scriptText.value = data.content
    scriptStats.value = data.stats
    await loadProjects()
    await loadScript()
    message.success('草稿已保存')
    return true
  } catch (error) {
    message.error(error instanceof Error ? error.message : '草稿保存失败')
    return false
  } finally {
    saving.value = false
  }
}

async function onFileChange({file}: { file?: { name?: string; file?: File } }) {
  const source = file?.file
  if (!source) return
  try {
    scriptText.value = await source.text()
    message.success(`已导入 ${file.name || '剧本文件'}，请保存草稿以写入服务器`)
  } catch {
    message.error('剧本文件读取失败')
  }
}

async function aiFormat() {
  formatting.value = true
  scriptText.value = scriptText.value.trim()
  await saveDraft()
  formatting.value = false
}

async function lockVersion() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  locking.value = true
  try {
    if (!isLocked.value && !await saveDraft()) return
    const data = await http.post<{
      document_id: number;
      title: string;
      version: ScriptVersion;
      content: string
    }>(`/projects/${projectId}/script/lock`)
    version.value = data.version
    scriptText.value = data.content
    await loadProjects()
    message.success('版本已锁定')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '版本锁定失败')
  } finally {
    locking.value = false
  }
}

function characterName(characterId: number | null | undefined) {
  return characters.value.find((item) => item.id === characterId)?.name || '旁白'
}

function versionStatus(status: string) {
  return ({
    DRAFT: '草稿',
    LOCKED: '已锁定',
    PUBLISHED: '已发布',
    ARCHIVED: '历史版本'
  } as Record<string, string>)[status] || status
}

function useSuggestion(suggestion: string) {
  chatInput.value = suggestion
  sendMessage()
}

function sendMessage() {
  const input = chatInput.value.trim()
  if (!input) return
  assistantMessage.value = `收到“${input}”，我会结合当前版本给出可执行的优化建议。`
  chatInput.value = ''
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch {
    // Keep rendering the cached workspace when the project API is unavailable.
  }
  await loadScript()
})

watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) loadScript()
})
</script>

<style scoped>
.editor-card :deep(.n-card__content),
.assistant-panel :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
}

.script-tabs :deep(.n-tabs-nav) {
  height: 49px;
  margin: 0;
  border-bottom: 1px solid #e8eaf0;
}

.script-tabs :deep(.n-tabs-tab) {
  min-width: 82px;
  justify-content: center;
  padding: 0;
  font-size: 12px;
}

.script-tabs :deep(.n-tab-pane) {
  padding: 0;
}

.script-editor :deep(.n-input-wrapper) {
  padding: 0;
}

.script-editor :deep(.n-input__textarea-el) {
  min-height: 310px !important;
  padding: 20px 18px;
  color: #515d73;
  font-size: 14px;
  line-height: 1.8;
}

.quality-alert :deep(.n-alert-body) {
  padding: 11px;
}

.quality-alert :deep(.n-alert-header) {
  font-size: 12px;
  font-weight: 400;
}

@media (max-width: 640px) {
  .editor-card :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
