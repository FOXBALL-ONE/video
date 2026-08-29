<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[18px] text-[#20283b] sm:px-[27px]">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">03 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div><h1 class="m-0 text-[31px] font-medium">分镜管理</h1><p class="mt-[11px] text-[13px] text-[#8a94aa]">景别、运镜、动作与连续性，所有修改均纳入版本记录和质量门禁。</p></div>
          <div class="flex w-full gap-2 sm:w-auto"><n-button class="!h-[42px] !rounded-[10px]" secondary @click="notify('草稿已保存')">保存草稿</n-button><n-button class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost aria-label="发布项目" /></div>
        </div>
      </header>

      <ProductionSteps active="shots" />

      <section class="grid min-h-[820px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card class="shots-panel !rounded-[17px] !border-[#dfe3eb]" :content-style="{ padding: '0 25px 24px' }">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">分镜设计与镜头调度</h2><p class="mt-[9px] text-xs text-[#8994a8]">逐镜管理画面、动作、运镜、台词、时长和生成提示词</p></div>
            <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="addShot">＋ 新增镜头</n-button>
          </div>

          <div class="grid min-h-[507px] grid-cols-1 pt-5 sm:grid-cols-[255px_1fr]">
            <aside class="flex overflow-x-auto border-b border-[#e8eaf0] pb-3 sm:block sm:border-b-0 sm:border-r sm:pb-0 sm:pr-5">
              <button
                v-for="shot in shots"
                :key="shot.id"
                type="button"
                class="grid min-h-[62px] min-w-[210px] grid-cols-[24px_1fr_auto] rounded-[10px] px-2 py-3 text-left text-[#7d899d] sm:min-w-0 sm:w-full"
                :class="currentShot === shot.id ? 'bg-[#efecff] text-[#5a4ef0]' : 'bg-transparent'"
                @click="selectShot(shot)"
              >
                <strong class="text-xs font-medium">{{ shot.id }}</strong>
                <span class="flex min-w-0 flex-col"><b class="text-xs font-medium text-[#4b566b]">{{ shot.title }}</b><small class="mt-[7px] truncate text-[10px]">{{ shot.meta }}</small></span>
                <n-tag size="tiny" :bordered="false" :type="shot.tone === 'done' ? 'success' : 'warning'">{{ shot.status }}</n-tag>
              </button>
            </aside>

            <div class="py-[15px] sm:py-0 sm:pl-5">
              <div class="flex h-[230px] items-end justify-between rounded-[13px] bg-[linear-gradient(130deg,#333b50,#a16d7f)] p-[14px] text-xs text-white">
                <span>镜头 {{ currentShot }}</span><n-button secondary size="small" class="!text-[#5c50ed]" @click="notify('动态预览已启动')">▶ 预览动态</n-button>
              </div>
              <n-form label-placement="top" class="grid grid-cols-1 gap-x-[11px] pt-3 sm:grid-cols-2">
                <n-form-item label="镜头标题"><n-input v-model:value="form.title" /></n-form-item>
                <n-form-item label="场景 / 时间"><n-input v-model:value="form.scene" /></n-form-item>
                <n-form-item label="景别与运镜"><n-input v-model:value="form.camera" type="textarea" :rows="2" /></n-form-item>
                <n-form-item label="人物动作"><n-input v-model:value="form.action" type="textarea" :rows="2" /></n-form-item>
                <n-form-item label="对白 / 旁白"><n-input v-model:value="form.dialogue" type="textarea" :rows="2" /></n-form-item>
                <n-form-item label="生成提示词"><n-input v-model:value="form.prompt" type="textarea" :rows="2" /></n-form-item>
              </n-form>
            </div>
          </div>

          <div class="flex min-h-[57px] flex-wrap items-start gap-[10px] border-t border-[#e8eaf0] py-[15px] text-[10px] sm:items-center sm:py-0">
            <strong class="mr-[3px] text-[11px]">连续性检查</strong><n-tag size="small" :bordered="false" type="success">✓ 轴线正确</n-tag><n-tag size="small" :bordered="false" type="success">✓ 角色服装一致</n-tag><n-tag size="small" :bordered="false" type="warning">! 镜头 02 → 03 景别变化不足</n-tag><n-button class="sm:!ml-auto !text-[#ad7423]" secondary size="small" @click="notify('连续性问题已修复')">一键修复</n-button>
          </div>
        </n-card>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="分镜管理助手" @send="updateAssistant" />
      </section>
    </div>
  </main>
</template>

<script setup>
import { ref } from 'vue'

const message = useMessage()
const shots = ref([
  { id: '01', title: '雨夜独行', meta: '城市街道 · 夜 · 外 · 4.0s', status: '已完成', tone: 'done' },
  { id: '02', title: '末班车站', meta: '公交站台 · 夜 · 外 · 5.5s', status: '待优化', tone: 'wait' },
  { id: '03', title: '陌生来信', meta: '站台座椅 · 夜 · 外 · 3.5s', status: '已完成', tone: 'done' },
  { id: '04', title: '未来声音', meta: '公交车内 · 夜 · 内 · 6.0s', status: '待生成', tone: 'wait' }
])
const currentShot = ref('01')
const form = ref({ title: '雨夜独行', scene: '城市街道 · 夜 · 外', camera: '远景推进至中景，跟随林夏', action: '林夏停下脚步，抬头看向站牌，雨水顺着发梢落下', dialogue: '林夏（低声）：又错过了。', prompt: '电影感雨夜街道，青年女性独行，蓝紫霓虹反射在湿地，镜头缓慢推进' })
const assistantMessage = ref('我是分镜导演助手。可以检查景别变化、轴线、动作连续性，并把每个镜头整理为视频模型提示词。')
const assistantSuggestions = ['优化镜头节奏', '检查轴线连续性', '补全运镜提示词']

function notify(value) { message.success(value) }
function selectShot(shot) { currentShot.value = shot.id; form.value.title = shot.title }
function addShot() { shots.value.push({ id: String(shots.value.length + 1).padStart(2, '0'), title: '新镜头', meta: '待设置场景 · 3.0s', status: '待编辑', tone: 'wait' }); message.success('已新增镜头') }
function updateAssistant(value) { assistantMessage.value = `收到“${value}”，我会结合当前分镜给出调度建议。` }
</script>

<style scoped>
.shots-panel :deep(.n-form-item) {
  margin-bottom: 4px;
}

.shots-panel :deep(.n-form-item-label) {
  color: #7d899d;
  font-size: 11px;
}

@media (max-width: 640px) {
  .shots-panel :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
