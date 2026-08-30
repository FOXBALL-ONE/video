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
            <n-button class="!h-[42px] !rounded-[10px] !border-[#665bff] !text-[#5b4ff0]" ghost @click="openCreate">添加资产</n-button>
          </div>
        </div>
      </header>
      <ProductionSteps active="assets" />
      <section class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="asset-panel !rounded-[17px] !border-[#dfe3eb]">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">{{ hasEpisode ? '本集资产清单' : '项目资产清单' }}</h2><p class="mt-2 text-xs text-[#8994a8]">编辑后即时保存，状态用于追踪资产准备进度。</p></div>
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
const message = useMessage(); const http = useHttp(); const route = useRoute(); const { activeProject, loadProjects } = useProjectWorkspace(); const { activeEpisode } = useEpisodeWorkspace()
const assets = ref<AssetItem[]>([]); const loading = ref(false); const saving = ref(false); const completeness = ref(0); const search = ref(''); const selectedFilter = ref('ALL'); const editorVisible = ref(false); const editing = ref<AssetItem | null>(null)
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
async function loadAssets() { if (!Number.isInteger(projectId.value) || projectId.value <= 0) return; loading.value = true; try { const data = await http.get<AssetResponse>(episodePath.value); assets.value = data.list || []; completeness.value = data.completeness || 0 } catch (error) { message.error(error instanceof Error ? error.message : '资产加载失败') } finally { loading.value = false } }
function openCreate() { editing.value = null; Object.assign(draft, { name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT' }); editorVisible.value = true }
function openEdit(asset: AssetItem) { editing.value = asset; Object.assign(draft, { name: asset.name, type: asset.type, description: asset.description || '', reference_url: asset.reference_url || '', notes: asset.notes || '', status: asset.status }); editorVisible.value = true }
async function submitAsset() { if (!draft.name.trim()) { message.warning('请填写资产名称'); return }; saving.value = true; try { const payload = { name: draft.name.trim(), type: draft.type, description: draft.description, reference_url: draft.reference_url || '', notes: draft.notes || '', status: draft.status }; if (editing.value) await http.put(`${episodePath.value}/${editing.value.id}`, payload); else await http.post(episodePath.value, payload); editorVisible.value = false; await loadAssets(); message.success('资产已保存') } catch (error) { message.error(error instanceof Error ? error.message : '资产保存失败') } finally { saving.value = false } }
async function removeAsset(asset: AssetItem) { try { await http.delete(`${episodePath.value}/${asset.id}`); await loadAssets(); message.success('资产已删除') } catch (error) { message.error(error instanceof Error ? error.message : '资产删除失败') } }
async function saveDraft() { saving.value = true; try { await loadAssets(); message.success('资产草稿已保存') } finally { saving.value = false } }
function updateAssistant(value: string) { assistantMessage.value = `收到“${value}”，我会整理当前剧集的执行建议。` }
onMounted(async () => { try { await loadProjects() } catch {} await loadAssets() }); watch(() => [route.params.episodeId, activeProject.value?.id], loadAssets)
</script>
<style scoped>@media (max-width: 640px) { .asset-panel :deep(.n-card__content) { padding-left: 14px !important; padding-right: 14px !important; } }</style>
