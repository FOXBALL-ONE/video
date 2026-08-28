<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[27px] text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">02 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div><h1 class="m-0 text-[31px] font-medium leading-[1.18]">资产清单</h1><p class="mt-[11px] text-[13px] text-[#8a94aa]">角色、场景、道具与声音，所有修改均纳入版本记录和质量门禁。</p></div>
          <div class="flex w-full gap-2 sm:w-auto"><n-button class="!h-[42px] !rounded-[10px]" secondary @click="notify('草稿已保存')">保存草稿</n-button><n-button class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost aria-label="发布项目" /></div>
        </div>
      </header>

      <ProductionSteps active="assets" />

      <section class="grid min-h-[820px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card class="asset-panel !rounded-[17px] !border-[#dfe3eb]" :content-style="{ padding: '0 25px 24px' }">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">项目资产清单</h2><p class="mt-[9px] text-xs text-[#8994a8]">从剧本自动提取并管理资产版本、一致性、版权和生成节点</p></div>
            <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="addAsset">＋ 添加资产</n-button>
          </div>

          <div class="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <n-button-group class="max-w-full overflow-x-auto">
              <n-button v-for="filter in filters" :key="filter" :type="selectedFilter === filter ? 'primary' : 'default'" :color="selectedFilter === filter ? '#6654ee' : undefined" @click="selectedFilter = filter">{{ filter }}</n-button>
            </n-button-group>
            <n-input v-model:value="search" class="w-full sm:!w-[164px]" placeholder="搜索资产、标签或负责人" clearable />
          </div>

          <div class="grid grid-cols-1 gap-[14px] sm:grid-cols-2">
            <n-card v-for="asset in filteredAssets" :key="asset.name" class="asset-item min-h-[136px] !overflow-hidden !rounded-xl !border-[#dfe3eb]" :content-style="{ padding: 0 }">
              <div class="flex min-h-[136px]">
                <div class="flex w-[110px] shrink-0 flex-col items-center justify-center text-white" :style="{ background: asset.color }">
                  <n-tag size="small" :bordered="false" class="self-start !ml-[10px] !bg-[rgba(31,40,59,0.26)] !text-white">{{ asset.type }}</n-tag>
                  <strong class="mt-3 text-[32px] leading-none">{{ asset.short }}</strong>
                </div>
                <div class="min-w-0 flex-1 px-3 pb-[10px] pt-[14px]">
                  <div class="flex items-center justify-between gap-1"><strong class="text-[13px] font-medium">{{ asset.name }}</strong><n-checkbox v-model:checked="asset.checked" size="small">{{ asset.status }}</n-checkbox></div>
                  <p class="mt-2 truncate text-[11px] text-[#7e899e]">{{ asset.description }}</p>
                  <small class="mt-[6px] block text-[10px] text-[#929bad]">负责人：王美术 · 版本 v2.1</small>
                  <div class="mt-3 flex gap-[7px]"><n-button text class="!text-[11px] !text-[#5a4ef0]" @click="notify(`正在编辑${asset.name}`)">编辑详情</n-button><n-button text class="!text-[11px] !text-[#5a4ef0]" @click="notify('生成提示词已加入队列')">✦ 生成提示词</n-button></div>
                </div>
              </div>
            </n-card>
          </div>

          <n-alert class="mt-[17px] !rounded-[10px] !border-[#f3dfb5] !bg-[#fff9ed]" type="warning" :show-icon="false">
            <div class="flex flex-col items-start gap-2 text-[11px] text-[#9a7b48] sm:flex-row sm:items-center"><strong class="shrink-0 text-xs text-[#ae7620]">资产完整度 68%</strong><span class="flex-1">顾川缺少三视图｜旧信封版权来源｜林夏声线未绑定接口</span><n-button secondary size="small" @click="notify('AI 补齐任务已创建')">让 AI 补齐</n-button></div>
          </n-alert>
        </n-card>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="资产清单助手" @send="updateAssistant" />
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'

const message = useMessage()
const filters = ['全部', '角色', '场景', '道具', '声音']
const selectedFilter = ref('全部')
const search = ref('')
const assets = ref([
  { name: '林夏', short: '林', type: '角色', status: '已锁定', checked: true, color: 'linear-gradient(135deg,#c2b7fb,#8068ef)', description: '24岁，短发，米白风衣，清冷气质' },
  { name: '顾川', short: '顾', type: '角色', status: '待确认', checked: false, color: 'linear-gradient(135deg,#98bfee,#4b8de1)', description: '28岁，黑衬衣，克制沉稳，左眼下小痣' },
  { name: '雨夜车站', short: '雨', type: '场景', status: '已锁定', checked: true, color: 'linear-gradient(135deg,#f4c9ab,#e9935e)', description: '蓝紫霓虹，潮湿反光，旧式站牌' },
  { name: '旧信封', short: '旧', type: '道具', status: '缺少版权', checked: false, color: 'linear-gradient(135deg,#dbc099,#bd9156)', description: '泛黄牛皮纸，蓝色邮戳，手写姓名' },
  { name: '林夏声线', short: '林', type: '声音', status: '待生成', checked: false, color: 'linear-gradient(135deg,#a4dcc4,#43b589)', description: '青年女声，低沉，轻微气声，语速0.9' },
  { name: '悬疑氛围乐', short: '悬', type: '声音', status: '已锁定', checked: true, color: 'linear-gradient(135deg,#c8abe9,#9564d2)', description: '72BPM，钢琴与低频弦乐' }
])
const filteredAssets = computed(() => assets.value.filter((asset) => (selectedFilter.value === '全部' || asset.type === selectedFilter.value) && (!search.value || `${asset.name}${asset.description}`.includes(search.value))))
const assistantMessage = ref('我是资产统筹助手。当前有 3 项资产缺口，我可以补充一致性描述、版权来源和生成提示词。')
const assistantSuggestions = ['检查资产缺口', '完善角色一致性', '整理批量生成提示词']

function notify(value) { message.success(value) }
function addAsset() { message.success('已新增待填写资产') }
function updateAssistant(value) { assistantMessage.value = `收到“${value}”，我会整理相关资产的执行建议。` }
</script>

<style scoped>
@media (max-width: 640px) {
  .asset-panel :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
