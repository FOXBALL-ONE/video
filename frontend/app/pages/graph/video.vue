<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[18px] text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">04 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div><h1 class="m-0 text-[31px] font-medium">视频制作</h1>
            <p class="mt-[11px] text-[13px] text-[#8a94aa]">画面、配音、音效与合成，所有修改均纳入版本记录和质量门禁。</p>
          </div>
          <div class="flex w-full gap-2 sm:w-auto">
            <n-button :loading="saving" class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿
            </n-button>
            <n-button aria-label="发布项目"
                      class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost/>
          </div>
        </div>
      </header>

      <ProductionSteps active="video"/>

      <section class="grid min-h-[820px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="video-panel !rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">视频制作与合成</h2>
              <p class="mt-[9px] text-xs text-[#8994a8]">按依赖执行画面生成、角色配音、音乐音效、字幕和智能合成</p></div>
            <n-button :loading="saving" class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost
                      @click="saveDraft">制作设置
            </n-button>
          </div>

          <div
              class="grid grid-cols-1 gap-[18px] pt-5 md:grid-cols-[minmax(0,1.3fr)_minmax(230px,0.7fr)] xl:grid-cols-[390px_1fr]">
            <div
                class="flex h-[330px] flex-col items-center justify-center rounded-[13px] bg-[#171e2e] text-white sm:h-[350px]">
              <n-button aria-label="播放预览" circle
                        class="!h-16 !w-16 !border-0 !bg-[#323d55] !text-[23px] !text-white" @click="togglePlaying">{{ playing ? 'Ⅱ' : '▶' }}
              </n-button>
              <strong class="mt-[19px] text-sm">{{ playing ? '正在预览' : '等待生成预览' }}</strong>
              <span class="mt-[9px] text-[11px] text-[#abb4c6]">{{ settings.ratio }} · {{
                  previewResolution
                }} · {{ settings.fps }}fps · 00:19</span>
              <div
                  class="mt-[76px] grid w-[90%] grid-cols-[auto_1fr_auto] items-center gap-[10px] text-[10px] text-[#aeb7c7]">
                <small>00:00</small>
                <n-progress :percentage="playing ? 42 : 0" :show-indicator="false" color="#818da4" rail-color="#3c465a"
                            type="line"/>
                <small>00:19</small></div>
            </div>

            <n-card class="!h-auto !rounded-[13px] !border-[#dfe3eb] md:!h-[350px]" size="small">
              <h3 class="m-0 text-sm font-medium">成片参数</h3>
              <n-form class="mt-[13px]" label-placement="top">
                <n-form-item label="画面规格">
                  <n-select v-model:value="settings.ratio" :options="ratioOptions"/>
                </n-form-item>
                <n-form-item label="视觉风格">
                  <n-select v-model:value="settings.style" :options="styleOptions"/>
                </n-form-item>
                <n-form-item label="帧率">
                  <n-select v-model:value="settings.fps" :options="fpsOptions"/>
                </n-form-item>
                <n-form-item label="字幕">
                  <n-select v-model:value="settings.subtitle" :options="subtitleOptions"/>
                </n-form-item>
              </n-form>
            </n-card>
          </div>

          <div class="mt-5 grid grid-cols-2 gap-[9px] sm:grid-cols-3 xl:grid-cols-5">
            <n-card v-for="module in modules" :key="module.title"
                    class="module-card min-h-[158px] cursor-pointer !rounded-[11px] !border-[#dfe3eb]" hoverable
                    @click="message.info(`${module.title}配置已打开`)">
              <div class="flex min-h-[124px] flex-col items-start"><span
                  class="h-7 text-[21px] text-[#6655ef]">{{ module.icon }}</span><strong
                  class="mt-[10px] text-xs">{{ module.title }}</strong><small
                  class="mt-[9px] text-[10px] text-[#8b96aa]">{{ module.count }}</small>
                <n-tag :bordered="false" class="mt-[9px]" size="tiny">{{ module.status }}</n-tag>
                <n-button class="!mt-auto !text-[10px] !text-[#5d50ed]" text>配置</n-button>
              </div>
            </n-card>
          </div>

          <div
              class="mt-[19px] flex min-h-[79px] flex-col items-start justify-between gap-[14px] border-t border-[#e8eaf0] py-[18px] sm:flex-row sm:items-center sm:py-0">
            <div><strong class="text-xs">质量门禁</strong>
              <p class="mt-2 text-[10px] text-[#8a95a8]">角色一致性 ≥90% · 清晰度 ≥85% · 音画同步误差 ≤80ms</p></div>
            <n-button :loading="queueRunning" class="!h-[46px] !rounded-[11px] !font-semibold" color="#6554ee"
                      type="primary" @click="startQueue">▶ {{
                queueRunning ? '制作队列运行中' : '启动制作队列'
              }}
            </n-button>
          </div>
        </n-card>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="视频制作助手"
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

interface VideoSettings {
  id: number;
  aspect_ratio: string;
  resolution: string;
  style: string;
  fps: number;
  subtitle_mode: string
}

interface ProductionTask {
  id: number;
  task_type: string;
  status: string;
  progress: number;
  error_message?: string | null;
  created_at?: string | null
}

interface VideoResponse {
  settings: VideoSettings;
  tasks: ProductionTask[];
  counts: Record<string, number>
}

interface QueueResponse {
  list: ProductionTask[]
}

const playing = ref(false)
const queueRunning = ref(false)
const saving = ref(false)
const settings = ref({ratio: '9:16', style: 'cinematic', fps: '24', subtitle: 'smart'})
const tasks = ref<ProductionTask[]>([])
const counts = ref<Record<string, number>>({})
const ratioOptions = [{label: '竖屏 9:16 · 1080P', value: '9:16'}, {label: '横屏 16:9 · 1080P', value: '16:9'}]
const styleOptions = [{label: '写实电影感', value: 'cinematic'}, {
  label: '日系动画',
  value: 'anime'
}, {label: '黑白胶片', value: 'film'}]
const fpsOptions = [{label: '24 fps', value: '24'}, {label: '30 fps', value: '30'}, {label: '60 fps', value: '60'}]
const subtitleOptions = [{label: '简体中文 · 智能断句', value: 'smart'}, {label: '关闭字幕', value: 'off'}]
const taskMeta: Record<string, { icon: string; title: string; fallback: string }> = {
  IMAGE_GENERATION: {icon: '▣', title: '画面生成', fallback: '镜头'},
  VOICEOVER: {icon: '♫', title: '角色配音', fallback: '台词'},
  MUSIC: {icon: '♫', title: '音乐音效', fallback: '音轨'},
  SOUND_EFFECT: {icon: '♫', title: '音效生成', fallback: '音效'},
  SUBTITLE: {icon: 'CC', title: '字幕生成', fallback: '字幕'},
  COMPOSITION: {icon: '✦', title: '智能合成', fallback: '成片'},
}
const statusLabel: Record<string, string> = {
  PENDING: '等待中',
  QUEUED: '排队中',
  RUNNING: '制作中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消'
}
const previewResolution = computed(() => settings.value.ratio === '9:16' ? '1080×1920' : '1920×1080')
const modules = computed(() => Object.entries(taskMeta).filter(([type]) => type !== 'SOUND_EFFECT' || counts.value[type]).map(([type, meta]) => {
  const task = tasks.value.find((item) => item.task_type === type);
  return {
    ...meta,
    title: meta.title,
    count: `${counts.value[type] || 0} 个${meta.fallback}`,
    status: statusLabel[task?.status || 'PENDING'] || task?.status || '等待中'
  }
}))
const assistantMessage = ref('我是视频制片助手。可以规划画面、配音、音效、字幕与合成任务的依赖和质量门禁。')
const assistantSuggestions = ['规划制作队列', '检查生成参数', '生成成片质检单']

async function loadVideo() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  try {
    const data = await http.get<VideoResponse>(`/projects/${projectId}/video`);
    settings.value = {
      ratio: data.settings.aspect_ratio,
      style: data.settings.style,
      fps: String(data.settings.fps),
      subtitle: data.settings.subtitle_mode.toLowerCase()
    };
    tasks.value = data.tasks || [];
    counts.value = data.counts || {}
  } catch (error) {
    message.error(error instanceof Error ? error.message : '视频数据加载失败')
  }
}

async function saveDraft() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  saving.value = true
  try {
    await http.put(`/projects/${projectId}/video/settings`, {
      aspect_ratio: settings.value.ratio,
      resolution: settings.value.ratio === '9:16' ? '1080x1920' : '1920x1080',
      style: settings.value.style,
      fps: Number(settings.value.fps),
      subtitle_mode: settings.value.subtitle.toUpperCase()
    });
    await loadVideo();
    message.success('制作设置已保存')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '制作设置保存失败')
  } finally {
    saving.value = false
  }
}

async function startQueue() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  queueRunning.value = true
  try {
    await http.post<QueueResponse>(`/projects/${projectId}/video/queue`);
    await loadVideo();
    message.success('视频制作队列已启动')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '制作队列启动失败')
  } finally {
    queueRunning.value = false
  }
}

function togglePlaying() {
  playing.value = !playing.value
}

function updateAssistant(value: string) {
  assistantMessage.value = `收到“${value}”，我会按制作依赖生成执行方案。`
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch { /* cached workspace */
  }
  await loadVideo()
})
watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) loadVideo()
})
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
