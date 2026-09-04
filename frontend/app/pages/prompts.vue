<template>
  <main class="prompt-page min-h-[calc(100vh-65px)] bg-[#f6f7fb] px-4 pb-16 pt-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1180px]">
      <header class="flex flex-col gap-5 border-b border-[#e1e5ee] pb-6 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-[11px] font-bold tracking-[1.2px] text-[#4f62c7]">WORKSPACE / PROMPT CATALOG</p>
          <h1 class="mt-3 text-[30px] font-medium leading-tight">提示词目录</h1>
          <p class="mt-2 max-w-[690px] text-[13px] leading-6 text-[#788399]">集中管理 AI 场景模板、版本状态和发布记录。任务只会使用已发布版本。</p>
        </div>
        <n-button secondary :loading="loading" @click="loadPrompts">刷新目录</n-button>
      </header>

      <div class="mt-6 grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.15fr)]">
        <section class="overflow-hidden rounded-[12px] border border-[#dfe3eb] bg-white">
          <div class="flex items-center justify-between border-b border-[#edf0f4] px-5 py-4">
            <div>
              <h2 class="m-0 text-sm font-semibold text-[#303b51]">已登记场景</h2>
              <p class="mt-1 text-[11px] text-[#929bad]">{{ prompts.length }} 个提示词定义</p>
            </div>
            <n-select v-model:value="statusFilter" size="small" class="!w-[130px]" :options="statusOptions" clearable placeholder="全部状态" @update:value="loadPrompts" />
          </div>
          <div v-if="loading && !prompts.length" class="p-10 text-center text-xs text-[#8994a8]">正在加载提示词目录...</div>
          <div v-else-if="!prompts.length" class="p-10 text-center text-xs text-[#8994a8]">暂无可见的提示词定义。</div>
          <div v-else class="divide-y divide-[#edf0f4]">
            <button
              v-for="prompt in prompts"
              :key="prompt.id"
              type="button"
              class="flex w-full items-start justify-between gap-4 px-5 py-4 text-left transition-colors hover:bg-[#f8f9fd]"
              :class="selected?.id === prompt.id ? 'bg-[#f2f4ff]' : ''"
              @click="selectPrompt(prompt)"
            >
              <span class="min-w-0">
                <strong class="block truncate text-sm font-semibold text-[#303b51]">{{ prompt.display_name }}</strong>
                <span class="mt-1 block font-mono text-[11px] text-[#687388]">{{ prompt.key }}</span>
                <span class="mt-2 block text-[11px] text-[#9aa3b4]">{{ scopeLabel(prompt.scope_type) }} · 更新于 {{ formatDate(prompt.updated_at) }}</span>
              </span>
              <span class="shrink-0 text-right">
                <n-tag size="small" :bordered="false" :type="prompt.published_version_id ? 'success' : 'warning'">{{ prompt.published_version_id ? `v${prompt.published_version_no}` : '未发布' }}</n-tag>
                <span class="mt-2 block text-[10px] text-[#9aa3b4]">共 {{ prompt.latest_version_no || 0 }} 个版本</span>
              </span>
            </button>
          </div>
        </section>

        <section class="min-h-[540px] rounded-[12px] border border-[#dfe3eb] bg-white">
          <div v-if="!selected" class="flex min-h-[540px] items-center justify-center p-10 text-center text-xs text-[#8994a8]">选择左侧提示词查看版本详情。</div>
          <template v-else>
            <div class="flex flex-col gap-4 border-b border-[#edf0f4] px-5 py-4 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="m-0 font-mono text-[11px] text-[#687388]">{{ selected.key }}</p>
                <h2 class="mt-1.5 truncate text-lg font-semibold text-[#303b51]">{{ selected.display_name }}</h2>
                <p class="mt-1 text-[11px] text-[#929bad]">{{ scopeLabel(selected.scope_type) }} · {{ selected.purpose || '暂无用途说明' }}</p>
              </div>
              <div class="flex shrink-0 items-center gap-2">
                <n-select v-model:value="selectedVersionId" size="small" class="!w-[150px]" :options="versionOptions" :loading="versionsLoading" @update:value="loadSelectedVersion" />
                <n-button size="small" secondary :loading="detailLoading" @click="loadDetail">重新读取</n-button>
              </div>
            </div>

            <div v-if="detailLoading" class="p-8 text-center text-xs text-[#8994a8]">正在读取版本...</div>
            <div v-else-if="detail" class="space-y-5 p-5">
              <div class="flex flex-wrap items-center gap-2">
                <n-tag :bordered="false" :type="statusType(detail.status)" size="small">{{ statusLabel(detail.status) }}</n-tag>
                <span class="font-mono text-[11px] text-[#687388]">v{{ detail.version_no }} · {{ detail.locale }}</span>
                <span class="font-mono text-[10px] text-[#9aa3b4]">{{ detail.content_hash }}</span>
              </div>

              <div class="grid gap-3 sm:grid-cols-3">
                <div class="rounded-[9px] bg-[#f7f8fc] p-3"><span class="block text-[10px] uppercase tracking-wide text-[#9aa3b4]">消息</span><strong class="mt-1 block text-sm text-[#303b51]">{{ Array.isArray(detail.messages) ? detail.messages.length : 0 }} 条</strong></div>
                <div class="rounded-[9px] bg-[#f7f8fc] p-3"><span class="block text-[10px] uppercase tracking-wide text-[#9aa3b4]">变量</span><strong class="mt-1 block text-sm text-[#303b51]">{{ variableCount }} 个</strong></div>
                <div class="rounded-[9px] bg-[#f7f8fc] p-3"><span class="block text-[10px] uppercase tracking-wide text-[#9aa3b4]">发布于</span><strong class="mt-1 block text-sm text-[#303b51]">{{ formatDate(detail.published_at) }}</strong></div>
              </div>

              <div>
                <div class="mb-2 flex items-center justify-between"><h3 class="m-0 text-xs font-semibold text-[#4f596d]">消息模板</h3><span class="text-[10px] text-[#9aa3b4]">只读预览</span></div>
                <div class="space-y-2 rounded-[9px] border border-[#edf0f4] bg-[#fbfcfe] p-3">
                  <div v-for="(message, index) in messagePreview" :key="index" class="rounded-[7px] border border-[#edf0f4] bg-white px-3 py-2.5">
                    <div class="flex items-center justify-between"><span class="font-mono text-[10px] font-semibold uppercase text-[#4f62c7]">{{ message.role }}</span><span class="text-[10px] text-[#b0b6c2]">{{ message.content.length }} 字</span></div>
                    <p class="mt-2 max-h-24 overflow-hidden whitespace-pre-wrap break-words text-[11px] leading-5 text-[#687388]">{{ message.content }}</p>
                  </div>
                </div>
              </div>

              <div class="flex flex-wrap gap-2 border-t border-[#edf0f4] pt-4">
                <n-button v-if="detail.status === 'DRAFT'" size="small" secondary :loading="actionLoading" @click="openEditor">编辑草稿</n-button>
                <n-button v-if="detail.status === 'DRAFT'" size="small" type="primary" :loading="actionLoading" @click="changeStatus('submit-review')">提交评审</n-button>
                <n-button v-if="detail.status === 'IN_REVIEW'" size="small" type="primary" :loading="actionLoading" @click="changeStatus('publish')">发布版本</n-button>
                <n-button v-if="detail.status === 'IN_REVIEW'" size="small" secondary :loading="actionLoading" @click="changeStatus('reject-review')">退回草稿</n-button>
                <n-button v-if="detail.status === 'PUBLISHED'" size="small" secondary :loading="actionLoading" @click="changeStatus('archive')">归档版本</n-button>
                <n-button v-if="detail.status === 'PUBLISHED' || detail.status === 'ARCHIVED'" size="small" secondary :loading="actionLoading" @click="changeStatus('rollback')">复制为新草稿</n-button>
              </div>
            </div>
          </template>
        </section>
      </div>
    </div>

    <n-modal v-model:show="editorVisible" preset="card" title="编辑提示词草稿" class="!w-[min(900px,calc(100vw-32px))]" :mask-closable="!actionLoading">
      <n-form label-placement="top">
        <n-form-item label="消息模板 JSON" required>
          <n-input v-model:value="editorForm.messages_json" type="textarea" :autosize="{ minRows: 8, maxRows: 16 }" spellcheck="false" />
        </n-form-item>
        <div class="grid gap-4 lg:grid-cols-2">
          <n-form-item label="变量 Schema JSON" required>
            <n-input v-model:value="editorForm.variables_schema_json" type="textarea" :autosize="{ minRows: 12, maxRows: 22 }" spellcheck="false" />
          </n-form-item>
          <n-form-item label="输出 Schema JSON" required>
            <n-input v-model:value="editorForm.output_schema_json" type="textarea" :autosize="{ minRows: 12, maxRows: 22 }" spellcheck="false" />
          </n-form-item>
        </div>
        <n-form-item label="生成策略 JSON">
          <n-input v-model:value="editorForm.generation_policy_json" type="textarea" :autosize="{ minRows: 4, maxRows: 8 }" spellcheck="false" />
        </n-form-item>
        <n-form-item label="变更说明">
          <n-input v-model:value="editorForm.change_summary" maxlength="1000" placeholder="说明本次调整" />
        </n-form-item>
      </n-form>
      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button :disabled="actionLoading" @click="editorVisible = false">取消</n-button>
          <n-button type="primary" :loading="actionLoading" @click="saveEditor">保存草稿</n-button>
        </div>
      </template>
    </n-modal>
  </main>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useHttp} from '~/composables/useHttp'

interface PromptItem {
  id: number
  key: string
  display_name: string
  purpose?: string
  scope_type: string
  project_id?: number | null
  latest_version_no?: number | null
  latest_version_id?: number | null
  published_version_no?: number | null
  published_version_id?: number | null
  enabled: boolean
  updated_at?: string | null
}

interface PromptDetail {
  id: number
  prompt_id: number
  key: string
  display_name: string
  purpose?: string
  version_no: number
  locale: string
  status: string
  messages: Array<{role: string; content: string}>
  variables_schema: Record<string, unknown>
  output_schema: Record<string, unknown>
  generation_policy: Record<string, unknown>
  compatibility: Record<string, unknown>
  content_hash: string
  change_summary: string
  published_at?: string | null
}

interface PromptVersionItem {
  id: number
  version_no: number
  locale: string
  status: string
  content_hash: string
  change_summary: string
  published_at?: string | null
  updated_at?: string | null
}

const http = useHttp()
const message = useMessage()
const prompts = ref<PromptItem[]>([])
const selected = ref<PromptItem | null>(null)
const detail = ref<PromptDetail | null>(null)
const loading = ref(false)
const detailLoading = ref(false)
const versionsLoading = ref(false)
const actionLoading = ref(false)
const editorVisible = ref(false)
const statusFilter = ref<string | null>(null)
const editorForm = ref({messages_json: '', variables_schema_json: '', output_schema_json: '', generation_policy_json: '{}', compatibility_json: '{}', change_summary: ''})
const versions = ref<PromptVersionItem[]>([])
const selectedVersionId = ref<number | null>(null)
const statusOptions = [
  {label: '草稿', value: 'DRAFT'},
  {label: '评审中', value: 'IN_REVIEW'},
  {label: '已发布', value: 'PUBLISHED'},
  {label: '已归档', value: 'ARCHIVED'},
]

const variableCount = computed(() => {
  const properties = detail.value?.variables_schema?.properties
  return properties && typeof properties === 'object' ? Object.keys(properties).length : 0
})
const messagePreview = computed(() => (detail.value?.messages || []).map((item) => ({role: String(item.role || '').toLowerCase(), content: String(item.content || '')})))
const versionOptions = computed(() => versions.value.map((version) => ({label: `v${version.version_no} · ${statusLabel(version.status)}`, value: version.id})))

onMounted(loadPrompts)

async function loadPrompts() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {}
    if (statusFilter.value) params.status = statusFilter.value
    const response = await http.get<{list: PromptItem[]}>('/ai/prompts', params)
    prompts.value = response.list || []
    if (selected.value) {
      const current = prompts.value.find((item) => item.id === selected.value?.id)
      if (current) selected.value = current
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '提示词目录加载失败')
  } finally {
    loading.value = false
  }
}

async function selectPrompt(prompt: PromptItem) {
  selected.value = prompt
  await loadVersions()
}

async function loadDetail() {
  const prompt = selected.value
  const versionId = selectedVersionId.value || prompt?.latest_version_id || prompt?.published_version_id
  if (!prompt || !versionId) {
    detail.value = null
    return
  }
  detailLoading.value = true
  try {
    detail.value = await http.get<PromptDetail>(`/ai/prompts/${prompt.id}/versions/${versionId}`)
  } catch (error) {
    detail.value = null
    message.error(error instanceof Error ? error.message : '提示词版本加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function loadVersions() {
  const prompt = selected.value
  if (!prompt) return
  versionsLoading.value = true
  try {
    versions.value = await http.get<{list: PromptVersionItem[]}>(`/ai/prompts/${prompt.id}/versions`).then((response) => response.list || [])
    selectedVersionId.value = versions.value[0]?.id || prompt.latest_version_id || prompt.published_version_id || null
    await loadDetail()
  } catch (error) {
    versions.value = []
    selectedVersionId.value = null
    message.error(error instanceof Error ? error.message : '提示词版本列表加载失败')
  } finally {
    versionsLoading.value = false
  }
}

async function loadSelectedVersion() {
  await loadDetail()
}

async function changeStatus(action: string) {
  const prompt = selected.value
  const version = detail.value
  if (!prompt || !version) return
  actionLoading.value = true
  try {
    const response = await http.post<{id: number; version_no: number; status: string}>(`/ai/prompts/${prompt.id}/versions/${version.id}/${action}`)
    message.success(action === 'publish' ? '提示词版本已发布' : action === 'rollback' ? '已复制为新草稿' : '操作已完成')
    await loadPrompts()
    if (action === 'rollback') {
      const nextVersionId = response.id
      await loadVersions()
      selectedVersionId.value = nextVersionId
      detail.value = await http.get<PromptDetail>(`/ai/prompts/${prompt.id}/versions/${nextVersionId}`)
    } else {
      await loadVersions()
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '提示词版本操作失败')
  } finally {
    actionLoading.value = false
  }
}

function openEditor() {
  const value = detail.value
  if (!value || value.status !== 'DRAFT') return
  editorForm.value = {
    messages_json: JSON.stringify(value.messages, null, 2),
    variables_schema_json: JSON.stringify(value.variables_schema, null, 2),
    output_schema_json: JSON.stringify(value.output_schema, null, 2),
    generation_policy_json: JSON.stringify(value.generation_policy, null, 2),
    compatibility_json: JSON.stringify(value.compatibility || {}, null, 2),
    change_summary: value.change_summary || '',
  }
  editorVisible.value = true
}

async function saveEditor() {
  const prompt = selected.value
  const version = detail.value
  if (!prompt || !version) return
  const form = new FormData()
  Object.entries(editorForm.value).forEach(([key, value]) => form.append(key, value))
  form.append('locale', version.locale)
  actionLoading.value = true
  try {
    await http.put(`/ai/prompts/${prompt.id}/versions/${version.id}`, form)
    editorVisible.value = false
    message.success('提示词草稿已保存')
    await loadPrompts()
    await loadVersions()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '提示词草稿保存失败')
  } finally {
    actionLoading.value = false
  }
}

function scopeLabel(scope: string): string {
  return ({GLOBAL: '全局', PROJECT: '项目', USER: '个人'} as Record<string, string>)[scope] || scope
}

function statusLabel(status: string): string {
  return ({DRAFT: '草稿', IN_REVIEW: '评审中', PUBLISHED: '已发布', ARCHIVED: '已归档'} as Record<string, string>)[status] || status
}

function statusType(status: string): 'default' | 'success' | 'warning' | 'error' | 'info' {
  return ({DRAFT: 'default', IN_REVIEW: 'warning', PUBLISHED: 'success', ARCHIVED: 'info'} as Record<string, 'default' | 'success' | 'warning' | 'error' | 'info'>)[status] || 'default'
}

function formatDate(value?: string | null): string {
  return value ? value.replace('T', ' ').slice(0, 16) : '--'
}
</script>
