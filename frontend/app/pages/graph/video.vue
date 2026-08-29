<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[18px] text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">04 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div><h1 class="m-0 text-[31px] font-medium">视频制作</h1><p class="mt-[11px] text-[13px] text-[#8a94aa]">画面、配音、音效与合成，所有修改均纳入版本记录和质量门禁。</p></div>
          <div class="flex w-full gap-2 sm:w-auto"><n-button class="!h-[42px] !rounded-[10px]" secondary @click="notify('草稿已保存')">保存草稿</n-button><n-button class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost aria-label="发布项目" /></div>
        </div>
      </header>

      <ProductionSteps active="video" />

      <section class="grid min-h-[820px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card class="video-panel !rounded-[17px] !border-[#dfe3eb]" :content-style="{ padding: '0 25px 24px' }">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">视频制作与合成</h2><p class="mt-[9px] text-xs text-[#8994a8]">按依赖执行画面生成、角色配音、音乐音效、字幕和智能合成</p></div>
            <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="notify('制作设置已打开')">制作设置</n-button>
          </div>

          <div class="grid grid-cols-1 gap-[18px] pt-5 md:grid-cols-[minmax(0,1.3fr)_minmax(230px,0.7fr)] xl:grid-cols-[390px_1fr]">
            <div class="flex h-[330px] flex-col items-center justify-center rounded-[13px] bg-[#171e2e] text-white sm:h-[350px]">
              <n-button circle class="!h-16 !w-16 !border-0 !bg-[#323d55] !text-[23px] !text-white" aria-label="播放预览" @click="togglePlaying">{{ playing ? 'Ⅱ' : '▶' }}</n-button>
              <strong class="mt-[19px] text-sm">{{ playing ? '正在预览' : '等待生成预览' }}</strong>
              <span class="mt-[9px] text-[11px] text-[#abb4c6]">9:16 · 1080×1920 · 24fps · 00:19</span>
              <div class="mt-[76px] grid w-[90%] grid-cols-[auto_1fr_auto] items-center gap-[10px] text-[10px] text-[#aeb7c7]"><small>00:00</small><n-progress type="line" :percentage="playing ? 42 : 0" :show-indicator="false" color="#818da4" rail-color="#3c465a" /><small>00:19</small></div>
            </div>

            <n-card class="!h-auto !rounded-[13px] !border-[#dfe3eb] md:!h-[350px]" size="small">
              <h3 class="m-0 text-sm font-medium">成片参数</h3>
              <n-form label-placement="top" class="mt-[13px]">
                <n-form-item label="画面规格"><n-select v-model:value="settings.ratio" :options="ratioOptions" /></n-form-item>
                <n-form-item label="视觉风格"><n-select v-model:value="settings.style" :options="styleOptions" /></n-form-item>
                <n-form-item label="帧率"><n-select v-model:value="settings.fps" :options="fpsOptions" /></n-form-item>
                <n-form-item label="字幕"><n-select v-model:value="settings.subtitle" :options="subtitleOptions" /></n-form-item>
              </n-form>
            </n-card>
          </div>

          <div class="mt-5 grid grid-cols-2 gap-[9px] sm:grid-cols-3 xl:grid-cols-5">
            <n-card v-for="module in modules" :key="module.title" class="module-card min-h-[158px] cursor-pointer !rounded-[11px] !border-[#dfe3eb]" hoverable @click="notify(`${module.title}配置已打开`)">
              <div class="flex min-h-[124px] flex-col items-start"><span class="h-7 text-[21px] text-[#6655ef]">{{ module.icon }}</span><strong class="mt-[10px] text-xs">{{ module.title }}</strong><small class="mt-[9px] text-[10px] text-[#8b96aa]">{{ module.count }}</small><n-tag size="tiny" :bordered="false" class="mt-[9px]">{{ module.status }}</n-tag><n-button text class="!mt-auto !text-[10px] !text-[#5d50ed]">配置</n-button></div>
            </n-card>
          </div>

          <div class="mt-[19px] flex min-h-[79px] flex-col items-start justify-between gap-[14px] border-t border-[#e8eaf0] py-[18px] sm:flex-row sm:items-center sm:py-0">
            <div><strong class="text-xs">质量门禁</strong><p class="mt-2 text-[10px] text-[#8a95a8]">角色一致性 ≥90% · 清晰度 ≥85% · 音画同步误差 ≤80ms</p></div>
            <n-button class="!h-[46px] !rounded-[11px] !font-semibold" type="primary" color="#6554ee" :loading="queueRunning" @click="startQueue">▶ {{ queueRunning ? '制作队列运行中' : '启动制作队列' }}</n-button>
          </div>
        </n-card>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="视频制作助手" @send="updateAssistant" />
      </section>
    </div>
  </main>
</template>

<script setup>
import { ref } from 'vue'

const message = useMessage()
const playing = ref(false)
const queueRunning = ref(false)
const settings = ref({ ratio: '9:16', style: 'cinematic', fps: '24', subtitle: 'smart' })
const ratioOptions = [{ label: '竖屏 9:16 · 1080P', value: '9:16' }, { label: '横屏 16:9 · 1080P', value: '16:9' }]
const styleOptions = [{ label: '写实电影感', value: 'cinematic' }, { label: '日系动画', value: 'anime' }, { label: '黑白胶片', value: 'film' }]
const fpsOptions = [{ label: '24 fps', value: '24' }, { label: '30 fps', value: '30' }, { label: '60 fps', value: '60' }]
const subtitleOptions = [{ label: '简体中文 · 智能断句', value: 'smart' }, { label: '关闭字幕', value: 'off' }]
const modules = [
  { icon: '▣', title: '画面生成', count: '4 个镜头', status: '等待中' },
  { icon: '♫', title: '角色配音', count: '6 条台词', status: '依赖画面' },
  { icon: '♫', title: '音乐音效', count: '3 条音轨', status: '可并行' },
  { icon: 'CC', title: '字幕生成', count: '6 条字幕', status: '依赖配音' },
  { icon: '✦', title: '智能合成', count: '1 个成片', status: '依赖全部' }
]
const assistantMessage = ref('我是视频制片助手。可以规划画面、配音、音效、字幕与合成任务的依赖和质量门禁。')
const assistantSuggestions = ['规划制作队列', '检查生成参数', '生成成片质检单']

function notify(value) { message.success(value) }
function togglePlaying() { playing.value = !playing.value }
function startQueue() { queueRunning.value = true; message.success('视频制作队列已启动'); window.setTimeout(() => { queueRunning.value = false }, 1800) }
function updateAssistant(value) { assistantMessage.value = `收到“${value}”，我会按制作依赖生成执行方案。` }
</script>

<style scoped>
.video-panel :deep(.n-form-item) {
  margin-bottom: 9px;
}

.video-panel :deep(.n-form-item-label) {
  color: #8390a4;
  font-size: 11px;
}

.module-card :deep(.n-card__content) {
  padding: 18px 12px 12px;
}

@media (max-width: 640px) {
  .video-panel :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
