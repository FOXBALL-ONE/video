<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-5 border-b border-[#e5e8ef] pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">WORKSPACE · API</p>
          <h1 class="mt-4 text-[31px] font-medium leading-[1.18]">API 管理</h1>
          <p class="mt-3 max-w-[650px] text-[13px] leading-6 text-[#8a94aa]">
            维护 OpenAI 兼容模型服务、访问凭据与独立网络路由。</p>
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
          <div v-if="loading && !configs.length" class="p-10 text-center text-xs text-[#8994a8]">正在加载 API 配置...</div>
          <div v-else-if="!configs.length" class="p-10 text-center text-xs text-[#8994a8]">尚未添加 API 配置。</div>
          <div v-else class="overflow-x-auto">
            <table class="min-w-[1050px] w-full border-collapse text-left">
              <thead class="border-b border-[#edf0f4] bg-[#fafbfc] text-[11px] font-medium text-[#8994a8]">
              <tr>
                <th class="px-5 py-3">提供商</th>
                <th class="px-5 py-3">API 类型</th>
                <th class="px-5 py-3">模型 ID</th>
                <th class="px-5 py-3">服务地址</th>
                <th class="px-5 py-3">网络路由</th>
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
                  <n-tag :bordered="false" size="small">{{ apiTypeLabel(config.api_type) }}</n-tag>
                </td>
                <td class="px-5 py-4">
                  <span class="block max-w-[160px] truncate font-medium text-[#4b566b]">{{ config.model_id }}</span>
                </td>
                <td class="max-w-[230px] px-5 py-4">
                  <span class="block truncate text-[#687388]">{{ config.base_url }}</span>
                </td>
                <td class="max-w-[230px] px-5 py-4">
                  <span class="block truncate font-medium text-[#4b566b]">{{ transportSummary(config) }}</span>
                  <span class="mt-1 block truncate text-[10px] text-[#9aa3b4]">{{ transportDetails(config) }}</span>
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
             class="!w-[min(720px,calc(100vw-32px))]" preset="card">
      <n-form label-placement="top" @submit.prevent="saveConfig">
        <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
          <n-form-item label="提供商" required>
            <n-input v-model:value="form.provider" :disabled="Boolean(editingId)" placeholder="例如：openai"/>
          </n-form-item>
          <n-form-item label="API 类型" required>
            <n-select v-model:value="form.apiType" :disabled="Boolean(editingId)" :options="apiTypeOptions"/>
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

        <n-divider class="!my-5">网络连接</n-divider>
        <div class="flex items-center justify-between gap-4 rounded-lg border border-[#edf0f4] bg-[#fafbfc] px-4 py-3">
          <div>
            <p class="m-0 text-xs font-medium text-[#4b566b]">使用应用默认网络配置</p>
            <p class="mt-1 text-[10px] text-[#929bad]">默认配置由服务端环境维护。</p>
          </div>
          <n-switch v-model:value="form.useDefaultTransport"/>
        </div>

        <div v-if="!form.useDefaultTransport" class="mt-4 rounded-lg border border-[#edf0f4] p-4">
          <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
            <n-form-item label="代理模式" required>
              <n-select v-model:value="form.proxyMode" :options="proxyModeOptions"/>
            </n-form-item>
            <n-form-item label="DNS 解析方式">
              <n-select v-model:value="form.dnsMode" :disabled="form.proxyMode === 'DIRECT'" :options="dnsModeOptions"/>
            </n-form-item>
          </div>

          <template v-if="form.proxyMode !== 'DIRECT'">
            <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
              <n-form-item label="代理主机" required>
                <n-input v-model:value="form.proxyHost" placeholder="例如：127.0.0.1"/>
              </n-form-item>
              <n-form-item label="代理端口" required>
                <n-input-number v-model:value="form.proxyPort" :max="65535" :min="1" :show-button="false" class="!w-full" placeholder="例如：7890"/>
              </n-form-item>
              <n-form-item label="代理用户名">
                <n-input v-model:value="form.proxyUsername" autocomplete="off" placeholder="可选"/>
              </n-form-item>
              <n-form-item :label="form.proxyPasswordConfigured ? '代理密码（留空则不更新）' : '代理密码'">
                <n-input v-model:value="form.proxyPassword" autocomplete="new-password" placeholder="可选" show-password-on="click" type="password"/>
              </n-form-item>
            </div>
            <n-form-item label="不走代理的主机">
              <n-input v-model:value="form.nonProxyHosts" placeholder="多个主机用逗号分隔，例如：localhost,*.internal"/>
            </n-form-item>
            <n-form-item v-if="form.dnsMode === 'PROXY'" label="允许代理远程解析的主机" required>
              <n-input v-model:value="form.proxyRemoteDnsAllowedHosts" placeholder="多个主机用逗号分隔，例如：api.openai.com,*.example.com"/>
            </n-form-item>
          </template>

          <div class="grid grid-cols-1 gap-x-4 sm:grid-cols-2">
            <n-form-item label="连接超时（秒）">
              <n-input-number v-model:value="form.connectTimeoutSeconds" :max="300" :min="1" :show-button="false" class="!w-full"/>
            </n-form-item>
            <n-form-item label="响应超时（秒）">
              <n-input-number v-model:value="form.responseTimeoutSeconds" :max="900" :min="1" :show-button="false" class="!w-full"/>
            </n-form-item>
          </div>
          <div class="flex items-center justify-between gap-4 border-t border-[#edf0f4] pt-3">
            <div>
              <p class="m-0 text-xs font-medium text-[#4b566b]">允许私网地址</p>
              <p class="mt-1 text-[10px] text-[#929bad]">仅在服务部署于受控网络时启用。</p>
            </div>
            <n-switch v-model:value="form.allowPrivateNetwork"/>
          </div>
        </div>

        <div class="mt-5 flex items-center justify-between border-t border-[#edf0f4] pt-4">
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

type ProxyMode = 'DIRECT' | 'HTTP' | 'SOCKS5'
type DnsMode = 'LOCAL' | 'PROXY'
type ApiType = 'CHAT' | 'IMAGE' | 'VIDEO'

interface ProviderConfig {
  id: number
  provider: string
  api_type: ApiType
  model_id: string
  base_url: string
  enabled: boolean
  use_default_transport: boolean
  proxy_mode: ProxyMode
  proxy_host: string | null
  proxy_port: number | null
  proxy_username: string | null
  proxy_password_configured: boolean
  non_proxy_hosts: string[]
  dns_mode: DnsMode
  proxy_remote_dns_allowed_hosts: string[]
  allow_private_network: boolean
  connect_timeout_seconds: number
  response_timeout_seconds: number
  created_at?: string
  updated_at?: string
}

interface ProviderForm {
  provider: string
  apiType: ApiType
  modelId: string
  baseUrl: string
  apiKey: string
  enabled: boolean
  useDefaultTransport: boolean
  proxyMode: ProxyMode
  proxyHost: string
  proxyPort: number | null
  proxyUsername: string
  proxyPassword: string
  proxyPasswordConfigured: boolean
  nonProxyHosts: string
  dnsMode: DnsMode
  proxyRemoteDnsAllowedHosts: string
  allowPrivateNetwork: boolean
  connectTimeoutSeconds: number | null
  responseTimeoutSeconds: number | null
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
const proxyModeOptions = [
  {label: '直连', value: 'DIRECT'},
  {label: 'HTTP 代理', value: 'HTTP'},
  {label: 'SOCKS5 代理', value: 'SOCKS5'},
]
const dnsModeOptions = [
  {label: '本地解析', value: 'LOCAL'},
  {label: '代理解析', value: 'PROXY'},
]
const apiTypeOptions = [
  {label: '对话', value: 'CHAT'},
  {label: '图片', value: 'IMAGE'},
  {label: '视频', value: 'VIDEO'},
]

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
    apiType: config.api_type || 'CHAT',
    modelId: config.model_id,
    baseUrl: config.base_url,
    apiKey: '',
    enabled: config.enabled,
    useDefaultTransport: config.use_default_transport ?? true,
    proxyMode: config.proxy_mode || 'DIRECT',
    proxyHost: config.proxy_host || '',
    proxyPort: config.proxy_port ?? null,
    proxyUsername: config.proxy_username || '',
    proxyPassword: '',
    proxyPasswordConfigured: config.proxy_password_configured ?? false,
    nonProxyHosts: (config.non_proxy_hosts || []).join(', '),
    dnsMode: config.dns_mode || 'LOCAL',
    proxyRemoteDnsAllowedHosts: (config.proxy_remote_dns_allowed_hosts || []).join(', '),
    allowPrivateNetwork: config.allow_private_network ?? false,
    connectTimeoutSeconds: config.connect_timeout_seconds ?? 10,
    responseTimeoutSeconds: config.response_timeout_seconds ?? 90,
  })
  showForm.value = true
}

async function fetchModels() {
  if (!form.useDefaultTransport && form.proxyMode !== 'DIRECT' && (!form.proxyHost.trim() || !form.proxyPort)) {
    message.warning('请填写代理主机和端口')
    return
  }
  if (!form.useDefaultTransport && form.dnsMode === 'PROXY' && !form.proxyRemoteDnsAllowedHosts.trim()) {
    message.warning('请填写允许代理远程解析的主机')
    return
  }

  const formData = new FormData()
  formData.append('base_url', form.baseUrl.trim())
  formData.append('api_type', form.apiType)
  if (form.provider.trim()) formData.append('provider', form.provider.trim())
  if (form.apiKey.trim()) formData.append('api_key', form.apiKey.trim())
  if (editingId.value) formData.append('config_id', String(editingId.value))
  formData.append('use_default_transport', String(form.useDefaultTransport))
  formData.append('proxy_mode', form.proxyMode)
  if (form.proxyHost.trim()) formData.append('proxy_host', form.proxyHost.trim())
  if (form.proxyPort) formData.append('proxy_port', String(form.proxyPort))
  if (form.proxyUsername.trim()) formData.append('proxy_username', form.proxyUsername.trim())
  if (form.proxyPassword.trim()) formData.append('proxy_password', form.proxyPassword)
  if (form.nonProxyHosts.trim()) formData.append('non_proxy_hosts', form.nonProxyHosts.trim())
  formData.append('dns_mode', form.proxyMode === 'DIRECT' ? 'LOCAL' : form.dnsMode)
  if (form.proxyRemoteDnsAllowedHosts.trim()) formData.append('proxy_remote_dns_allowed_hosts', form.proxyRemoteDnsAllowedHosts.trim())
  formData.append('allow_private_network', String(form.allowPrivateNetwork))
  formData.append('connect_timeout_seconds', String(form.connectTimeoutSeconds || 10))
  formData.append('response_timeout_seconds', String(form.responseTimeoutSeconds || 90))

  fetchingModels.value = true
  try {
    const response = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
    modelOptions.value = response.list.map((model) => ({label: model.id, value: model.id}))
    if (!modelOptions.value.length) message.warning('服务未返回可用模型')
    else message.success(`已获取 ${modelOptions.value.length} 个模型`)
  } catch (error) {
    message.error(requestMessage(error, '获取模型列表失败'))
  } finally {
    fetchingModels.value = false
  }
}

async function saveConfig() {
  if (!form.provider.trim() || !form.modelId.trim() || !form.baseUrl.trim() || (!editingId.value && !form.apiKey.trim())) {
    message.warning('请填写所有必填字段')
    return
  }
  if (!form.useDefaultTransport && form.proxyMode !== 'DIRECT' && (!form.proxyHost.trim() || !form.proxyPort)) {
    message.warning('请填写代理主机和端口')
    return
  }
  if (!form.useDefaultTransport && form.dnsMode === 'PROXY' && !form.proxyRemoteDnsAllowedHosts.trim()) {
    message.warning('请填写允许代理远程解析的主机')
    return
  }
  if (!form.connectTimeoutSeconds || !form.responseTimeoutSeconds) {
    message.warning('请填写有效的超时设置')
    return
  }

  const payload = {
    provider: form.provider.trim(),
    api_type: form.apiType,
    model_id: form.modelId.trim(),
    base_url: form.baseUrl.trim(),
    api_key: form.apiKey.trim(),
    enabled: form.enabled,
    use_default_transport: form.useDefaultTransport,
    proxy_mode: form.proxyMode,
    proxy_host: form.proxyHost.trim() || undefined,
    proxy_port: form.proxyPort || undefined,
    proxy_username: form.proxyUsername.trim() || undefined,
    proxy_password: form.proxyPassword.trim() || undefined,
    non_proxy_hosts: form.nonProxyHosts.trim() || undefined,
    dns_mode: form.proxyMode === 'DIRECT' ? 'LOCAL' : form.dnsMode,
    proxy_remote_dns_allowed_hosts: form.proxyRemoteDnsAllowedHosts.trim() || undefined,
    allow_private_network: form.allowPrivateNetwork,
    connect_timeout_seconds: form.connectTimeoutSeconds,
    response_timeout_seconds: form.responseTimeoutSeconds,
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

function transportSummary(config: ProviderConfig): string {
  if (config.use_default_transport) return '应用默认'
  if (config.proxy_mode === 'DIRECT') return '直连'
  const label = config.proxy_mode === 'HTTP' ? 'HTTP 代理' : 'SOCKS5 代理'
  return `${label} · ${config.proxy_host || '--'}:${config.proxy_port || '--'}`
}

function transportDetails(config: ProviderConfig): string {
  if (config.use_default_transport) return '由服务端环境控制'
  if (config.proxy_mode === 'DIRECT') return config.allow_private_network ? '允许私网地址' : '仅公开网络'
  const items = [config.dns_mode === 'PROXY' ? '代理 DNS' : '本地 DNS']
  if (config.proxy_username) items.push('账号认证')
  if (config.proxy_password_configured) items.push('已设密码')
  return items.join(' · ')
}

function emptyForm(): ProviderForm {
  return {
    provider: '', apiType: 'CHAT', modelId: '', baseUrl: '', apiKey: '', enabled: true,
    useDefaultTransport: true, proxyMode: 'DIRECT', proxyHost: '', proxyPort: null,
    proxyUsername: '', proxyPassword: '', proxyPasswordConfigured: false, nonProxyHosts: '', dnsMode: 'LOCAL',
    proxyRemoteDnsAllowedHosts: '', allowPrivateNetwork: false, connectTimeoutSeconds: 10, responseTimeoutSeconds: 90,
  }
}

function apiTypeLabel(type: ApiType | string | undefined): string {
  return apiTypeOptions.find((option) => option.value === type)?.label || '对话'
}

function requestMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message ? error.message : fallback
}
</script>
