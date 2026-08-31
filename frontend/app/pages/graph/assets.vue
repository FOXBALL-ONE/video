<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">03 / 07 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-5 sm:flex-row sm:items-end">
          <div>
            <h1 class="m-0 text-[31px] font-medium leading-[1.18]">每集资产管理</h1>
            <p class="mt-2 text-[13px] text-[#8a94aa]">角色、场景背景、道具与服饰按当前剧集独立维护。</p>
            <p v-if="hasEpisode && activeEpisode" class="mt-2 text-xs text-[#6654ee]">当前剧集：{{ activeEpisode.title }}</p>
          </div>
          <div class="flex w-full gap-2 sm:w-auto">
            <n-button :loading="saving" class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿</n-button>
            <n-button v-if="hasEpisode" class="!h-[42px] !rounded-[10px] !border-[#ff9b4a] !text-[#e87517]" ghost @click="openExtraction">AI 提取资产</n-button>
            <n-button class="!h-[42px] !rounded-[10px] !border-[#665bff] !text-[#5b4ff0]" ghost @click="openCreate">添加资产</n-button>
          </div>
        </div>
      </header>
      <ProductionSteps active="assets" />
      <section class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="asset-panel !rounded-[17px] !border-[#dfe3eb]">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">{{ hasEpisode ? '本集资产清单' : '项目资产清单' }}</h2><p class="mt-2 text-xs text-[#8994a8]">编辑后即时保存，状态用于追踪资产准备进度。</p></div>
            <n-button v-if="hasEpisode" class="!rounded-[10px] !border-[#ffcf9f] !text-[#e87517]" ghost @click="openExtraction">提取场景/角色/道具</n-button>
            <n-button class="!rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="openCreate">＋ 添加资产</n-button>
          </div>
          <div class="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <n-button-group class="max-w-full overflow-x-auto"><n-button v-for="filter in filters" :key="filter.key" :type="selectedFilter === filter.key ? 'primary' : 'default'" :color="selectedFilter === filter.key ? '#6654ee' : undefined" @click="selectedFilter = filter.key">{{ filter.label }}</n-button></n-button-group>
            <n-input v-model:value="search" class="w-full sm:!w-[190px]" clearable placeholder="搜索名称或描述" />
          </div>
          <div v-if="loading" class="py-16 text-center text-sm text-[#929bad]">正在加载资产...</div>
          <div v-else-if="!filteredAssets.length" class="border border-dashed border-[#dfe3eb] px-5 py-16 text-center text-sm text-[#929bad]">暂无资产，点击“添加资产”开始维护。</div>
          <div v-else class="grid grid-cols-1 gap-[14px] sm:grid-cols-2">
            <n-card v-for="asset in filteredAssets" :key="asset.id" :content-style="{ padding: 0 }" class="min-h-[174px] !overflow-hidden !rounded-xl !border-[#dfe3eb]">
              <div class="flex min-h-[174px]"><div :style="{ background: colorByType[asset.type] || colorByType.PROP }" class="flex w-[106px] shrink-0 flex-col items-center justify-center text-white"><n-tag :bordered="false" class="self-start !ml-2 !bg-[rgba(31,40,59,0.26)] !text-white" size="small">{{ typeLabel[asset.type] }}</n-tag><strong class="mt-3 text-[32px] leading-none">{{ asset.name.slice(0, 1) }}</strong></div><div class="min-w-0 flex-1 px-3 pb-3 pt-3"><div class="flex items-start justify-between gap-2"><strong class="truncate text-[13px] font-medium">{{ asset.name }}</strong><n-tag size="small" :type="asset.status === 'LOCKED' || asset.status === 'READY' ? 'success' : 'warning'">{{ statusLabel[asset.status] }}</n-tag></div><p class="mt-2 line-clamp-2 min-h-[32px] text-[11px] text-[#7e899e]">{{ asset.description || '暂无描述' }}</p><small class="mt-2 block truncate text-[10px] text-[#929bad]">{{ asset.reference_url || '未添加参考图链接' }}</small><div class="mt-3 flex gap-2"><n-button size="tiny" class="!text-[#5a4ef0]" text @click="openEdit(asset)">编辑详情</n-button><n-button size="tiny" class="!text-[#a34d4d]" text @click="removeAsset(asset)">删除</n-button></div></div></div>
            </n-card>
          </div>
          <n-alert v-if="assets.length" :show-icon="false" class="mt-4 !rounded-[10px] !border-[#f3dfb5] !bg-[#fff9ed]" type="warning"><strong class="text-xs text-[#ae7620]">资产完整度 {{ completeness }}%</strong><span class="ml-3 text-[11px] text-[#9a7b48]">{{ completeness === 100 ? '本集资产已全部就绪。' : '仍有资产处于草稿或待生成状态。' }}</span></n-alert>
        </n-card>
        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="资产清单助手" @send="updateAssistant" />
      </section>
      <n-modal v-model:show="extractionVisible" preset="card" title="提取场景/角色/道具" class="w-[min(94vw,620px)]" :mask-closable="false">
        <p class="-mt-2 text-xs text-[#8a94aa]">根据剧本内容，AI 将按选择的模型分别提取资产。</p>
        <div class="mt-4 space-y-3">
          <div v-for="job in extractionJobs" :key="job.type" class="rounded-[12px] border border-[#ffb36d] bg-[#fff9f1] p-3.5">
            <div class="flex items-start gap-3">
              <n-checkbox v-model:checked="job.enabled" class="mt-1" />
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-start justify-between gap-2">
                  <div><span class="text-[11px] text-[#8a94aa]">{{ job.agentLabel }}</span><strong class="mt-0.5 block text-sm text-[#20283b]">{{ job.label }}</strong></div>
                  <span class="text-[11px] text-[#e87517]">3 积分</span>
                </div>
                <p class="mt-2 text-[11px] leading-5 text-[#7e899e]">{{ job.description }}</p>
                <div class="mt-3 border-t border-[#f2dec8] pt-3">
                  <label class="mb-1.5 block text-[11px] text-[#8994a8]">选择模型</label>
                  <n-select v-model:value="job.modelKey" :options="modelOptions" :disabled="!modelOptions.length" placeholder="请先配置模型" size="small" />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-if="extractionError" class="mt-4 rounded-[9px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]" role="alert">
          <strong class="block font-medium">提取失败：{{ extractionError.message }}</strong>
          <div class="mt-1.5 grid grid-cols-1 gap-1 text-[11px] text-[#8e6262] sm:grid-cols-2">
            <span>错误代码：{{ extractionError.error_code ?? '未返回' }}</span><span>服务提供商：{{ extractionError.provider || '未返回' }}</span>
            <span class="sm:col-span-2">请求 ID：{{ extractionError.request_id || '未返回' }}</span>
          </div>
          <pre v-if="extractionError.response_content" class="mt-2 max-h-28 overflow-auto whitespace-pre-wrap break-words rounded-md bg-white px-2 py-1.5 text-[10px] leading-4 text-[#765858]">{{ extractionError.response_content }}</pre>
        </div>
        <template #footer>
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><span class="text-xs text-[#8994a8]">预估消耗：{{ selectedExtractionCount * 3 }} 积分</span><div class="flex justify-end gap-2"><n-button :disabled="extracting" @click="extractionVisible = false">取消</n-button><n-button color="#16a34a" type="primary" :loading="extracting" :disabled="!selectedExtractionCount || !modelOptions.length" @click="startExtraction">开始提取</n-button></div></div>
        </template>
      </n-modal>
      <n-modal v-model:show="editorVisible" preset="card" :title="editing ? '编辑资产详情' : '添加本集资产'" class="w-[min(92vw,560px)]" :mask-closable="false">
        <n-form label-placement="top">
          <n-form-item label="名称"><n-input v-model:value="draft.name" placeholder="如：林夏、雨夜街道、旧相机" /></n-form-item>
          <n-form-item label="类型"><n-select v-model:value="draft.type" :options="typeOptions" /></n-form-item>
          <n-form-item label="描述"><n-input v-model:value="draft.description" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" placeholder="外观、用途、出场要求或连续性备注" /></n-form-item>
          <n-form-item label="参考图链接"><n-input v-model:value="draft.reference_url" placeholder="可选，填写图片或素材地址" /></n-form-item>
          <n-form-item label="状态"><n-select v-model:value="draft.status" :options="statusOptions" /></n-form-item>
        </n-form>
        <template #footer><div class="flex justify-end gap-2"><n-button @click="editorVisible = false">取消</n-button><n-button type="primary" :loading="saving" @click="submitAsset">保存</n-button></div></template>
      </n-modal>
    </div>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
interface AssetItem { id: number; name: string; type: string; description: string | null; reference_url: string | null; notes: string | null; status: string; updated_at: string | null }
interface AssetResponse { list: AssetItem[]; completeness: number }
interface ProviderConfig { id: number; provider: string; model_id: string; base_url: string; enabled: boolean }
interface ModelOption { label: string; value: string }
interface ExtractionError { message: string; error_code?: number | null; response_content?: string | null; provider?: string; request_id?: string }
interface ExtractionJob { type: string; label: string; agentLabel: string; description: string; enabled: boolean; modelKey: string }
const message = useMessage(); const http = useHttp(); const route = useRoute(); const { activeProject, loadProjects } = useProjectWorkspace(); const { activeEpisode } = useEpisodeWorkspace()
const assets = ref<AssetItem[]>([]); const loading = ref(false); const saving = ref(false); const completeness = ref(0); const search = ref(''); const selectedFilter = ref('ALL'); const editorVisible = ref(false); const editing = ref<AssetItem | null>(null)
const extractionVisible = ref(false); const extracting = ref(false); const extractionError = ref<ExtractionError | null>(null); const providers = ref<ProviderConfig[]>([]); const modelOptions = ref<ModelOption[]>([])
const extractionJobs = reactive<ExtractionJob[]>([
  { type: 'SCENE_BACKGROUND', label: '场景提取智能体', agentLabel: '场景/背景智能体', description: '提取剧本中的场景、地点、时间、光线和视觉氛围。', enabled: true, modelKey: '' },
  { type: 'CHARACTER', label: '角色提取智能体', agentLabel: '角色/发型智能体', description: '提取人物身份、外貌、性格、关系和出场要求。', enabled: true, modelKey: '' },
  { type: 'PROP', label: '道具提取智能体', agentLabel: '道具/物件智能体', description: '提取对剧情有推动作用的关键道具与重要物件。', enabled: true, modelKey: '' },
  { type: 'COSTUME', label: '服装提取智能体', agentLabel: '服装/造型智能体', description: '提取人物服装、配饰、色彩和连续性要求。', enabled: false, modelKey: '' },
])
const draft = reactive({ name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT' })
const filters = [{ key: 'ALL', label: '全部' }, { key: 'CHARACTER', label: '角色' }, { key: 'SCENE_BACKGROUND', label: '场景背景' }, { key: 'SCENE', label: '场景' }, { key: 'PROP', label: '道具' }, { key: 'COSTUME', label: '服饰' }, { key: 'VOICE', label: '声音' }]
const typeLabel: Record<string, string> = { CHARACTER: '角色', SCENE_BACKGROUND: '场景背景', SCENE: '场景', PROP: '道具', COSTUME: '服饰', VOICE: '声音', MUSIC: '音乐', SOUND_EFFECT: '音效' }
const statusLabel: Record<string, string> = { DRAFT: '草稿', PENDING_CONFIRMATION: '待确认', LOCKED: '已锁定', NEEDS_COPYRIGHT: '缺少版权', TO_GENERATE: '待生成', READY: '已就绪', ARCHIVED: '已归档' }
const colorByType: Record<string, string> = { CHARACTER: 'linear-gradient(135deg,#9a8bea,#6556d9)', SCENE_BACKGROUND: 'linear-gradient(135deg,#eda977,#d87947)', PROP: 'linear-gradient(135deg,#c9a66f,#a8783d)', COSTUME: 'linear-gradient(135deg,#de8da8,#b74c72)', VOICE: 'linear-gradient(135deg,#66bfa7,#278d76)', MUSIC: 'linear-gradient(135deg,#ae8bd4,#7852ad)', SOUND_EFFECT: 'linear-gradient(135deg,#79aee4,#447ab7)' }
const filteredAssets = computed(() => assets.value.filter((item) => (selectedFilter.value === 'ALL' || item.type === selectedFilter.value) && (!search.value || `${item.name}${item.description || ''}`.toLowerCase().includes(search.value.toLowerCase()))))
const assistantMessage = ref('我是资产统筹助手。可以帮助检查本集资产缺口和连续性。'); const assistantSuggestions = ['检查本集缺口', '完善角色一致性', '整理服饰清单']
const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id)); const episodeId = computed(() => Number(route.params.episodeId || '')); const hasEpisode = computed(() => Number.isInteger(episodeId.value) && episodeId.value > 0); const episodePath = computed(() => hasEpisode.value ? `/projects/${projectId.value}/episodes/${episodeId.value}/assets` : `/projects/${projectId.value}/assets`)
const typeOptions = computed(() => (hasEpisode.value ? filters.filter((item) => ['CHARACTER', 'SCENE_BACKGROUND', 'PROP', 'COSTUME', 'VOICE'].includes(item.key)) : filters.filter((item) => ['CHARACTER', 'SCENE', 'PROP', 'VOICE'].includes(item.key))).map((item) => ({ label: item.label, value: item.key })).concat([{ label: '音乐', value: 'MUSIC' }, { label: '音效', value: 'SOUND_EFFECT' }]))
const statusOptions = Object.entries(statusLabel).map(([value, label]) => ({ value, label }))
const selectedExtractionCount = computed(() => extractionJobs.filter((job) => job.enabled).length)
async function loadAssets() { if (!Number.isInteger(projectId.value) || projectId.value <= 0) return; loading.value = true; try { const data = await http.get<AssetResponse>(episodePath.value); assets.value = data.list || []; completeness.value = data.completeness || 0 } catch (error) { message.error(error instanceof Error ? error.message : '资产加载失败') } finally { loading.value = false } }
async function loadProviders() {
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    providers.value = response.list.filter((item) => item.enabled)
    const options: ModelOption[] = []
    for (const provider of providers.value) {
      if (provider.model_id) options.push({ label: `${provider.provider} · ${provider.model_id}`, value: `${provider.id}::${provider.model_id}` })
      try {
        const formData = new FormData(); formData.append('base_url', provider.base_url); formData.append('config_id', String(provider.id))
        const models = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
        models.list.forEach((model) => { const value = `${provider.id}::${model.id}`; if (!options.some((option) => option.value === value)) options.push({ label: `${provider.provider} · ${model.id}`, value }) })
      } catch { /* Keep the configured default model when model discovery is unavailable. */ }
    }
    modelOptions.value = options
    extractionJobs.forEach((job) => { if (!job.modelKey) job.modelKey = options[0]?.value || '' })
  } catch { providers.value = []; modelOptions.value = [] }
}
function openExtraction() { extractionError.value = null; extractionJobs.forEach((job) => { if (!job.modelKey) job.modelKey = modelOptions.value[0]?.value || '' }); extractionVisible.value = true }
function openCreate() { editing.value = null; Object.assign(draft, { name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT' }); editorVisible.value = true }
function openEdit(asset: AssetItem) { editing.value = asset; Object.assign(draft, { name: asset.name, type: asset.type, description: asset.description || '', reference_url: asset.reference_url || '', notes: asset.notes || '', status: asset.status }); editorVisible.value = true }
async function submitAsset() { if (!draft.name.trim()) { message.warning('请填写资产名称'); return }; saving.value = true; try { const payload = { name: draft.name.trim(), type: draft.type, description: draft.description, reference_url: draft.reference_url || '', notes: draft.notes || '', status: draft.status }; if (editing.value) await http.put(`${episodePath.value}/${editing.value.id}`, payload); else await http.post(episodePath.value, payload); editorVisible.value = false; await loadAssets(); message.success('资产已保存') } catch (error) { message.error(error instanceof Error ? error.message : '资产保存失败') } finally { saving.value = false } }
async function removeAsset(asset: AssetItem) { try { await http.delete(`${episodePath.value}/${asset.id}`); await loadAssets(); message.success('资产已删除') } catch (error) { message.error(error instanceof Error ? error.message : '资产删除失败') } }
async function startExtraction() {
  const jobs = extractionJobs.filter((job) => job.enabled)
  if (!jobs.length) { message.warning('请至少选择一种资产类型'); return }
  if (jobs.some((job) => !job.modelKey)) { message.warning('请为每种资产选择模型'); return }
  extracting.value = true; extractionError.value = null
  try {
    for (const job of jobs) {
      const [providerConfigId, model] = job.modelKey.split('::')
      try {
        await http.post(`${episodePath.value}/extract`, { type: job.type, provider_config_id: Number(providerConfigId), model })
      } catch (error) {
        extractionError.value = extractExtractionError(error, '资产提取失败')
        throw error
      }
    }
    extractionVisible.value = false
    await loadAssets()
    message.success('资产提取完成')
  } catch (error) {
    if (!extractionError.value) message.error(error instanceof Error ? error.message : '资产提取失败')
  } finally { extracting.value = false }
}
function extractExtractionError(error: unknown, fallback: string): ExtractionError {
  const value = error as { message?: string; data?: { data?: { error?: ExtractionError }; message?: string } }
  const structured = value.data?.data?.error
  return structured ? { ...structured, message: value.data?.message || value.message || fallback } : { message: value.data?.message || value.message || fallback }
}
async function saveDraft() { saving.value = true; try { await loadAssets(); message.success('资产草稿已保存') } finally { saving.value = false } }
function updateAssistant(value: string) { assistantMessage.value = `收到“${value}”，我会整理当前剧集的执行建议。` }
onMounted(async () => { try { await loadProjects() } catch {} await Promise.all([loadAssets(), loadProviders()]) }); watch(() => [route.params.episodeId, activeProject.value?.id], loadAssets)
</script>
<style scoped>@media (max-width: 640px) { .asset-panel :deep(.n-card__content) { padding-left: 14px !important; padding-right: 14px !important; } }</style>
