<template>
  <div v-if="props.showEpisode && activeEpisode && !hasExplicitEpisodeRoute" class="mb-2 mt-[23px] flex items-center gap-2 text-xs text-[#8994a8]">
    <span class="h-1.5 w-1.5 rounded-full bg-[#6654ee]" aria-hidden="true"></span>
    <span>当前剧集</span><strong class="font-semibold text-[#4d5870]">{{ activeEpisode.title }}</strong>
  </div>
  <nav class="workflow-nav my-[23px] mt-[10px] border-b border-[#e7e9ef] bg-white px-4 sm:px-6" aria-label="制作流程">
    <div class="mx-auto flex max-w-[1060px] items-center justify-start overflow-x-auto py-2.5 sm:justify-center">
      <template v-for="(step, index) in steps" :key="step.id">
        <span
          v-if="index"
          class="mx-2 h-px w-5 shrink-0 sm:mx-3 sm:w-8"
          :class="index <= cursor ? 'bg-[#8bd3a7]' : 'bg-[#dfe2e8]'"
          aria-hidden="true"
        ></span>
        <NuxtLink
          :to="routes[step.id]"
          class="workflow-step flex shrink-0 items-center gap-1.5 rounded-md px-1.5 py-1 text-xs transition-colors focus:outline-none focus:ring-2 focus:ring-[#93c5fd] focus:ring-offset-1"
          :class="step.id === props.active ? 'font-semibold text-[#2563eb]' : index < cursor ? 'text-[#278d68] hover:bg-[#f1fbf5] hover:text-[#1f7a59]' : 'text-[#9299a7] hover:bg-[#f7f7fa] hover:text-[#596174]'"
          :aria-current="step.id === props.active ? 'step' : undefined"
          :title="step.id === props.active ? `${step.title}进行中` : index < cursor ? `${step.title}已完成` : `${step.title}待开始`"
        >
          <span
            class="flex h-5 w-5 items-center justify-center rounded-full text-[10px] font-semibold"
            :class="step.id === props.active ? 'bg-[#2563eb] text-white shadow-[0_0_0_3px_rgba(37,99,235,0.14)]' : index < cursor ? 'bg-[#2eae72] text-white' : 'bg-[#f0f1f4] text-[#8b93a2]'"
          >{{ index < cursor ? '✓' : index + 1 }}</span>
          <span class="whitespace-nowrap">{{ step.title }}</span>
        </NuxtLink>
      </template>
    </div>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ active: string; stage?: string }>(), { active: 'script', stage: 'SCRIPT' })
const route = useRoute()
const { activeProjectId } = useProjectWorkspace()
const { activeEpisodeId, activeEpisode } = useEpisodeWorkspace()
const projectId = computed(() => String(route.params.projectId || activeProjectId.value || ''))
const hasExplicitEpisodeRoute = computed(() => /^\d+$/.test(String(route.params.episodeId || '')))
const episodeRouteId = computed(() => {
  const id = String(route.params.episodeId || activeEpisodeId.value || '')
  return /^\d+$/.test(id) ? id : ''
})
const episodePrefix = computed(() => episodeRouteId.value ? `/projects/${projectId.value}/episodes/${episodeRouteId.value}/workflow` : `/projects/${projectId.value}/workflow`)

const steps = [
  { id: 'script', title: '剧本导入' },
  { id: 'tasks', title: '每集任务卡' },
  { id: 'assets', title: '资产管理' },
  { id: 'shots', title: '分镜规划' },
  { id: 'storyboard', title: '分镜图生成' },
  { id: 'video', title: '画面视频' },
  { id: 'export', title: '合成导出' }
]
const routes = computed(() => ({
  script: `${episodePrefix.value}/script`,
  tasks: `${episodePrefix.value}/tasks`,
  assets: `${episodePrefix.value}/assets`,
  shots: `${episodePrefix.value}/shots`,
  storyboard: `${episodePrefix.value}/storyboard`,
  video: `${episodePrefix.value}/video`,
  export: `${episodePrefix.value}/video`
}))
const activeIndex = computed(() => Math.max(0, steps.findIndex((step) => step.id === props.active)))
/** 制作节点（SCRIPT/ASSETS/SHOTS/VIDEO）落到步骤条上的位置：1 个节点包含剧本与任务卡两步，导出跟随视频节点。 */
const stageOrder = ['SCRIPT', 'ASSETS', 'SHOTS', 'VIDEO']
const stageCursor = computed(() => {
  const level = stageOrder.indexOf(String(props.stage || ''))
  return level < 0 ? 0 : Math.min(level * 2 + 1, steps.length - 1)
})
const cursor = computed(() => Math.max(activeIndex.value, stageCursor.value))
</script>

<style scoped>
.workflow-nav {
  scrollbar-width: thin;
}
</style>
