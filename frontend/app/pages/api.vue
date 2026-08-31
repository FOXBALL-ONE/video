<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-5 border-b border-[#e5e8ef] pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">WORKSPACE · API</p>
          <h1 class="mt-4 text-[31px] font-medium leading-[1.18]">API 管理</h1>
          <p class="mt-3 max-w-[650px] text-[13px] leading-6 text-[#8a94aa]">
            维护生成模型的服务地址、模型标识和访问凭据。</p>
        </div>
        <n-button class="!h-[42px] !rounded-[10px]" color="#6654ee" type="primary" @click="openCreate">
          新增 API 配置
        </n-button>
      </header>

      <section class="mt-7">
        <div class="mb-4 flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 class="m-0 text-lg font-medium">模型服务</h2>
            <p class="mt-1.5 text-xs text-[#8994a8]">{{ configs.length }} 个配置，{{ enabledCount }} 个已启用</p>
          </div>
          <n-button :loading="loading" secondary size="small" @click="loadConfigs">刷新</n-button>
        </div>

        <div class="overflow-hidden rounded-[12px] border border-[#dfe3eb] bg-white">
          <div v-if="loading && !configs.length" class="p-10 text-center text-xs text-[#8994a8]">正在加载 API 配置...
          </div>
          <div v-else-if="!configs.length" class="p-10 text-center text-xs text-[#8994a8]">尚未添加 API 配置。</div>
          <div v-else class="overflow-x-auto">
            <table class="min-w-[820px] w-full border-collapse text-left">
              <thead class="border-b border-[#edf0f4] bg-[#fafbfc] text-[11px] font-medium text-[#8994a8]">
              <tr>
                <th class="px-5 py-3">提供商</th>
                <th class="px-5 py-3">模型 ID</th>
                <th class="px-5 py-3">服务地址</th>
                <th class="px-5 py-3">状态</th>
                <th class="px-5 py-3">更新时间</th>
                <th class="px-5 py-3 text-right">操作</th>
              </tr>
              </thead>
              <tbody class="divide-y divide-[#edf0f4] text-xs text-[#4b566b]">
              <tr v-for="config in configs" :key="config.id" class="transition-colors hover:bg-[#fafaff]">
                <td class="px-5 py-4">
                  <strong class="block font-semibold text-[#303b51]">{{ config.provider }}</strong>
                  <span class="mt-1 block text-[10px] text-[#9aa3b4]">ID · {{ config.id }}</span>
                </td>
                <td class="px-5 py-4">
                  <span class="block max-w-[180px] truncate font-medium text-[#4b566b]">{{ config.model_id }}</span>
                </td>
                <td class="max-w-[290px] px-5 py-4">
                  <span class="block truncate text-[#687388]">{{ config.base_url }}</span>
                </td>
                <td class="px-5 py-4">
                  <n-tag :bordered="false" :type="config.enabled ? 'success' : 'default'" size="small">
                    {{ config.enabled ? '已启用' : '已停用' }}
                  </n-tag>
                </td>
                <td class="px-5 py-4 text-[11px] text-[#8994a8]">
                  {{ config.updated_at ? config.updated_at.replace('T', ' ').slice(0, 16) : '--' }}
                </td>
                <td class="px-5 py-4">
                  <div class="flex justify-end gap-1">
                    <n-button size="small" text type="primary" @click="openEdit(config)">编辑</n-button>
                    <n-popconfirm negative-text="取消" positive-text="删除" @positive-click="deleteConfig(config)">
                      <template #trigger>
                        <n-button size="small" text type="error">删除</n-button>
                      </template>
                      删除后无法恢复该 API 配置。
                    </n-popconfirm>
                  </div>
                </td>
              </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </div>

    <n-modal v-model:show="showForm" :mask-closable="!saving" :title="editingId ? '编辑 API 配置' : '新增 API 配置'"
             class="!w-[min(560px,calc(100vw-32px))]" preset="card">
      <n-form label-placement="top" @submit.prevent="saveConfig">
        <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <n-form-item label="提供商" required>
            <n-input v-model:value="form.provider" :disabled="Boolean(editingId)" placeholder="例如：openai"/>
          </n-form-item>
          <n-form-item label="模型 ID" required>
            <n-select v-if="modelOptions.length" v-model:value="form.modelId" :options="modelOptions" filterable placeholder="选择模型 ID"/>
            <n-input v-else v-model:value="form.modelId" placeholder="例如：gpt-4.1"/>
            <n-button :disabled="!form.baseUrl.trim() || (!editingId && !form.apiKey.trim())" :loading="fetchingModels" attr-type="button" class="!mt-2" size="small" @click="fetchModels">获取模型</n-button>
          </n-form-item>
        </div>
        <n-form-item label="API Base URL" required>
          <n-input v-model:value="form.baseUrl" placeholder="例如：https://api.openai.com"/>
        </n-form-item>
        <n-form-item :label="editingId ? 'API Key（留空则不更新）' : 'API Key'" required>
          <n-input v-model:value="form.apiKey" placeholder="输入服务访问密钥" show-password-on="click" type="password"/>
        </n-form-item>
        <div class="flex items-center justify-between border-t border-[#edf0f4] pt-4">
          <div>
            <p class="m-0 text-xs font-medium text-[#4b566b]">启用此配置</p>
            <p class="mt-1 text-[10px] text-[#929bad]">停用后不会被生产任务使用。</p>
          </div>
          <n-switch v-model:value="form.enabled"/>
        </div>
        <div class="mt-6 flex justify-end gap-2">
          <n-button :disabled="saving" @click="showForm = false">取消</n-button>
          <n-button :loading="saving" attr-type="submit" color="#6654ee" type="primary">保存配置</n-button>
        </div>
      </n-form>
    </n-modal>
  </main>
</template>

<script lang="ts" setup>
import {computed, onMounted, reactive, ref} from 'vue'

interface ProviderConfig {
  id: number
  provider: string
  model_id: string
  base_url: string
  enabled: boolean
  created_at?: string
  updated_at?: string
}

interface ProviderForm {
  provider: string
  modelId: string
  baseUrl: string
  apiKey: string
  enabled: boolean
}

const http = useHttp()
const message = useMessage()
const configs = ref<ProviderConfig[]>([])
const loading = ref(false)
const saving = ref(false)
const fetchingModels = ref(false)
const showForm = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<ProviderForm>(emptyForm())
const modelOptions = ref<{ label: string; value: string }[]>([])
const enabledCount = computed(() => configs.value.filter((config) => config.enabled).length)

onMounted(loadConfigs)

async function loadConfigs() {
  loading.value = true
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    configs.value = response.list
  } catch (error) {
    message.error(requestMessage(error, 'API 配置加载失败'))
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  modelOptions.value = []
  Object.assign(form, emptyForm())
  showForm.value = true
}

function openEdit(config: ProviderConfig) {
  editingId.value = config.id
  modelOptions.value = []
  Object.assign(form, {
    provider: config.provider,
    modelId: config.model_id,
    baseUrl: config.base_url,
    apiKey: '',
    enabled: config.enabled,
  })
  showForm.value = true
}

async function fetchModels() {
  const formData = new FormData()
  formData.append('base_url', form.baseUrl.trim())
  if (form.provider.trim()) formData.append('provider', form.provider.trim())
  if (form.apiKey.trim()) formData.append('api_key', form.apiKey.trim())
  if (editingId.value) formData.append('config_id', String(editingId.value))

  fetchingModels.value = true
  try {
    const response = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
    modelOptions.value = response.list.map((model) => ({ label: model.id, value: model.id }))
    if (!modelOptions.value.length) message.warning('服务未返回可用模型')
    else message.success(`已获取 ${modelOptions.value.length} 个模型`)
  } catch (error) {
    message.error(requestMessage(error, '获取模型列表失败'))
  } finally {
    fetchingModels.value = false
  }
}

async function saveConfig() {
  const payload = {
    provider: form.provider.trim(),
    model_id: form.modelId.trim(),
    base_url: form.baseUrl.trim(),
    api_key: form.apiKey.trim(),
    enabled: form.enabled,
  }
  if (!payload.provider || !payload.model_id || !payload.base_url || (!editingId.value && !payload.api_key)) {
    message.warning('请填写所有必填字段')
    return
  }

  saving.value = true
  try {
    if (editingId.value) await http.put(`/ai/model-providers/${editingId.value}`, payload)
    else await http.post('/ai/model-providers', payload)
    message.success(editingId.value ? 'API 配置已更新' : 'API 配置已添加')
    showForm.value = false
    await loadConfigs()
  } catch (error) {
    message.error(requestMessage(error, '保存 API 配置失败'))
  } finally {
    saving.value = false
  }
}

async function deleteConfig(config: ProviderConfig) {
  try {
    await http.delete(`/ai/model-providers/${config.id}`)
    configs.value = configs.value.filter((item) => item.id !== config.id)
    message.success('API 配置已删除')
  } catch (error) {
    message.error(requestMessage(error, '删除 API 配置失败'))
  }
}

function emptyForm(): ProviderForm {
  return {provider: '', modelId: '', baseUrl: '', apiKey: '', enabled: true}
}

function requestMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
</script>
