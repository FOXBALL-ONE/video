<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[27px] text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">02 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div><h1 class="m-0 text-[31px] font-medium leading-[1.18]">资产清单</h1>
            <p class="mt-[11px] text-[13px] text-[#8a94aa]">角色、场景、道具与声音，所有修改均纳入版本记录和质量门禁。</p>
          </div>
          <div class="flex w-full gap-2 sm:w-auto">
            <n-button :loading="saving" class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿
            </n-button>
            <n-button aria-label="发布项目"
                      class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost/>
          </div>
        </div>
      </header>

      <ProductionSteps active="assets"/>

      <section class="grid min-h-[820px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="asset-panel !rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">项目资产清单</h2>
              <p class="mt-[9px] text-xs text-[#8994a8]">从剧本自动提取并管理资产版本、一致性、版权和生成节点</p></div>
            <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="addAsset">＋
              添加资产
            </n-button>
          </div>

          <div class="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <n-button-group class="max-w-full overflow-x-auto">
              <n-button v-for="filter in filters" :key="filter"
                        :color="selectedFilter === filter ? '#6654ee' : undefined"
                        :type="selectedFilter === filter ? 'primary' : 'default'" @click="selectedFilter = filter">
                {{ filter }}
              </n-button>
            </n-button-group>
            <n-input v-model:value="search" class="w-full sm:!w-[164px]" clearable placeholder="搜索资产、标签或负责人"/>
          </div>

          <div class="grid grid-cols-1 gap-[14px] sm:grid-cols-2">
            <n-card v-for="asset in filteredAssets" :key="asset.name"
                    :content-style="{ padding: 0 }"
                    class="asset-item min-h-[136px] !overflow-hidden !rounded-xl !border-[#dfe3eb]">
              <div class="flex min-h-[136px]">
                <div :style="{ background: asset.color }"
                     class="flex w-[110px] shrink-0 flex-col items-center justify-center text-white">
                  <n-tag :bordered="false" class="self-start !ml-[10px] !bg-[rgba(31,40,59,0.26)] !text-white"
                         size="small">{{ asset.type }}
                  </n-tag>
                  <strong class="mt-3 text-[32px] leading-none">{{ asset.short }}</strong>
                </div>
                <div class="min-w-0 flex-1 px-3 pb-[10px] pt-[14px]">
                  <div class="flex items-center justify-between gap-1"><strong
                      class="text-[13px] font-medium">{{ asset.name }}</strong>
                    <n-checkbox v-model:checked="asset.checked" size="small"
                                @update:checked="toggleAsset(asset, $event)">{{ asset.status }}
                    </n-checkbox>
                  </div>
                  <p class="mt-2 truncate text-[11px] text-[#7e899e]">{{ asset.description || '暂无描述' }}</p>
                  <small class="mt-[6px] block text-[10px] text-[#929bad]">负责人：{{ asset.owner_id || '未分配' }} ·
                    {{ asset.status }}</small>
                  <div class="mt-3 flex gap-[7px]">
                    <n-button class="!text-[11px] !text-[#5a4ef0]" text @click="message.info(`正在编辑${asset.name}`)">
                      编辑详情
                    </n-button>
                    <n-button class="!text-[11px] !text-[#5a4ef0]" text
                              @click="message.success('生成提示词已加入队列')">✦ 生成提示词
                    </n-button>
                  </div>
                </div>
              </div>
            </n-card>
          </div>

          <n-alert :show-icon="false" class="mt-[17px] !rounded-[10px] !border-[#f3dfb5] !bg-[#fff9ed]" type="warning">
            <div class="flex flex-col items-start gap-2 text-[11px] text-[#9a7b48] sm:flex-row sm:items-center"><strong
                class="shrink-0 text-xs text-[#ae7620]">资产完整度 {{ completeness }}%</strong><span
                class="flex-1">{{ issueSummary }}</span>
              <n-button :disabled="!issues.length" :loading="resolving" secondary size="small" @click="resolveIssues">
                处理缺口
              </n-button>
            </div>
          </n-alert>
        </n-card>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="资产清单助手"
                        @send="updateAssistant"/>
      </section>
    </div>
  </main>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref, watch} from 'vue'

const message = useMessage()
const http = useHttp()
const {activeProject, loadProjects} = useProjectWorkspace()

interface AssetItem {
  id: number;
  name: string;
  type: string;
  description: string | null;
  status: string;
  owner_id: number | null;
  copyright_required: boolean;
  copyright_status: string;
  checked: boolean;
  short: string;
  color: string
}

interface AssetIssue {
  id: number;
  asset_id: number | null;
  issue_type: string;
  severity: string;
  description: string;
  resolved: boolean
}

interface AssetsResponse {
  list: Array<Omit<AssetItem, 'checked' | 'short' | 'color'>>;
  issues: AssetIssue[];
  completeness: number
}

const filters = ['全部', '角色', '场景', '道具', '声音']
const selectedFilter = ref('全部')
const search = ref('')
const assets = ref<AssetItem[]>([])
const issues = ref<AssetIssue[]>([])
const completeness = ref(0)
const saving = ref(false)
const resolving = ref(false)
const filteredAssets = computed(() => assets.value.filter((asset) => (selectedFilter.value === '全部' || asset.type === selectedFilter.value) && (!search.value || `${asset.name}${asset.description}`.includes(search.value))))
const issueSummary = computed(() => issues.value.length ? issues.value.slice(0, 3).map((item) => item.description).join('｜') : '当前项目没有未解决的资产缺口')
const assistantMessage = ref('我是资产统筹助手。当前有 3 项资产缺口，我可以补充一致性描述、版权来源和生成提示词。')
const assistantSuggestions = ['检查资产缺口', '完善角色一致性', '整理批量生成提示词']

const typeLabel: Record<string, string> = {
  CHARACTER: '角色',
  SCENE: '场景',
  PROP: '道具',
  VOICE: '声音',
  MUSIC: '音乐',
  SOUND_EFFECT: '音效'
}
const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_CONFIRMATION: '待确认',
  LOCKED: '已锁定',
  NEEDS_COPYRIGHT: '缺少版权',
  TO_GENERATE: '待生成',
  READY: '已就绪',
  ARCHIVED: '已归档'
}
const colorByType: Record<string, string> = {
  CHARACTER: 'linear-gradient(135deg,#c2b7fb,#8068ef)',
  SCENE: 'linear-gradient(135deg,#f4c9ab,#e9935e)',
  PROP: 'linear-gradient(135deg,#dbc099,#bd9156)',
  VOICE: 'linear-gradient(135deg,#a4dcc4,#43b589)',
  MUSIC: 'linear-gradient(135deg,#c8abe9,#9564d2)',
  SOUND_EFFECT: 'linear-gradient(135deg,#98bfee,#4b8de1)'
}

function applyAssets(data: AssetsResponse) {
  assets.value = (data.list || []).map((asset) => ({
    ...asset,
    type: typeLabel[asset.type] || asset.type,
    status: statusLabel[asset.status] || asset.status,
    checked: asset.status === 'LOCKED' || asset.status === 'READY',
    short: asset.name.slice(0, 1),
    color: colorByType[asset.type] || colorByType.PROP
  }))
  issues.value = data.issues || []
  completeness.value = data.completeness || 0
}

async function loadAssets() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  try {
    applyAssets(await http.get<AssetsResponse>(`/projects/${projectId}/assets`))
  } catch (error) {
    message.error(error instanceof Error ? error.message : '资产加载失败')
  }
}

async function saveDraft() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  saving.value = true
  try {
    await loadProjects();
    await loadAssets();
    message.success('资产草稿已保存')
  } finally {
    saving.value = false
  }
}

async function addAsset() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  try {
    await http.post(`/projects/${projectId}/assets`, {name: '新资产', type: 'PROP', description: '', status: 'DRAFT'});
    await loadAssets();
    message.success('已新增待填写资产')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '资产创建失败')
  }
}

async function toggleAsset(asset: AssetItem, checked: boolean) {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  const status = checked ? 'LOCKED' : 'DRAFT'
  try {
    await http.put(`/projects/${projectId}/assets/${asset.id}`, {
      name: asset.name,
      type: Object.entries(typeLabel).find(([, label]) => label === asset.type)?.[0] || 'PROP',
      description: asset.description || '',
      status
    });
    asset.status = statusLabel[status];
    message.success(`${asset.name}状态已更新`)
  } catch (error) {
    asset.checked = !checked;
    message.error(error instanceof Error ? error.message : '资产状态更新失败')
  }
}

async function resolveIssues() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  resolving.value = true
  try {
    await http.post(`/projects/${projectId}/assets/issues/resolve`);
    await loadAssets();
    message.success('资产缺口已处理')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '缺口处理失败')
  } finally {
    resolving.value = false
  }
}

function updateAssistant(value: string) {
  assistantMessage.value = `收到“${value}”，我会整理相关资产的执行建议。`
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch { /* cache fallback */
  }
  await loadAssets()
})
watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) loadAssets()
})
</script>

<style scoped>
@media (max-width: 640px) {
  .asset-panel :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
