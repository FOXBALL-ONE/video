<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-5 border-b border-[#e5e8ef] pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">VIDEO WORKSPACE · DASHBOARD</p>
          <h1 class="mt-4 text-[31px] font-medium leading-[1.18]">项目工作台</h1>
          <p class="mt-3 max-w-[650px] text-[13px] leading-6 text-[#8a94aa]">从项目出发，选择适合当前制作阶段的工作形式，继续完成剧本、资产、分镜和视频任务。</p>
        </div>
        <n-button type="primary" color="#6654ee" class="!h-[42px] !rounded-[10px]" @click="navigateTo({ path: '/projects', query: { create: '1' } })">新建项目</n-button>
      </header>

      <section v-if="activeProject" class="mt-7 grid grid-cols-1 gap-4 lg:grid-cols-[1.35fr_0.65fr]">
        <n-card class="!rounded-[15px] !border-[#d8d2ff] !bg-white" :content-style="{ padding: '24px' }">
          <div class="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
            <div class="flex min-w-0 items-start gap-3">
              <span class="flex h-12 w-12 shrink-0 items-center justify-center rounded-[12px] bg-[#6654ee] text-lg font-semibold text-white">{{ activeProject.name.slice(0, 1) }}</span>
              <div class="min-w-0">
                <p class="m-0 text-[11px] font-medium text-[#929bad]">当前项目 · {{ activeProject.code }}</p>
                <h2 class="mt-1.5 truncate text-xl font-semibold text-[#273148]">{{ activeProject.name }}</h2>
                <p class="mt-1.5 text-xs text-[#7e899e]">{{ activeProject.type }} · {{ activeProject.status }}</p>
              </div>
            </div>
            <n-button secondary class="!rounded-[9px] !text-[#5d52ec]" @click="navigateTo(`/projects/${activeProject.id}`)">查看项目</n-button>
          </div>
          <p class="mt-6 text-[13px] leading-6 text-[#687388]">{{ activeProject.description || '暂无项目简介' }}</p>
          <div class="mt-6 flex items-center justify-between text-xs text-[#8994a8]"><span>制作完成度</span><strong class="text-[#5d52ec]">{{ activeProject.progress }}%</strong></div>
          <n-progress :percentage="activeProject.progress" :show-indicator="false" class="mt-2" color="#6654ee" rail-color="#ececf4" type="line" />
          <div class="mt-6 grid grid-cols-3 gap-3 border-t border-[#edf0f4] pt-5 text-[11px]">
            <div><span class="block text-[#a0a8b8]">当前版本</span><strong class="mt-1 block font-medium text-[#4b566b]">{{ activeProject.version }}</strong></div>
            <div><span class="block text-[#a0a8b8]">预计时长</span><strong class="mt-1 block font-medium text-[#4b566b]">{{ activeProject.duration }}</strong></div>
            <div><span class="block text-[#a0a8b8]">质量评分</span><strong class="mt-1 block font-medium text-[#4b566b]">{{ activeProject.score }}</strong></div>
          </div>
        </n-card>

        <n-card class="!rounded-[15px] !border-[#dfe3eb] !bg-white" :content-style="{ padding: '24px' }">
          <p class="m-0 text-xs font-semibold text-[#4b566b]">项目状态</p>
          <div class="mt-5 flex items-end justify-between"><strong class="text-[34px] font-medium leading-none text-[#273148]">{{ activeProject.progress }}%</strong><span class="text-[11px] text-[#929bad]">{{ activeProject.pending }}</span></div>
          <div class="mt-5 rounded-[10px] border border-[#f3dfb5] bg-[#fff9ed] p-3 text-[11px] leading-5 text-[#9a7b48]">{{ activeProject.pendingDetail || '当前没有待处理事项' }}</div>
          <n-button block secondary class="mt-5 !rounded-[9px] !text-[#5d52ec]" @click="navigateTo(`/projects/${activeProject.id}/workflow/script`)">继续制作</n-button>
        </n-card>
      </section>

      <section v-if="activeProject" class="mt-7">
        <div class="mb-4 flex items-center justify-between"><h2 class="m-0 text-lg font-medium">选择工作形式</h2><span class="text-xs text-[#929bad]">{{ activeProject.name }}</span></div>
        <div class="grid grid-cols-1 gap-4 md:grid-cols-2">
          <n-card v-for="mode in workspaceModes" :key="mode.title" hoverable class="!rounded-[14px] !border-[#dfe3eb]" :content-style="{ padding: '20px' }" @click="navigateTo(mode.route(activeProject.id))">
            <div class="flex items-start gap-4"><span class="flex h-11 w-11 shrink-0 items-center justify-center rounded-[11px] text-xl" :class="mode.tone">{{ mode.icon }}</span><div><h3 class="m-0 text-sm font-semibold">{{ mode.title }}</h3><p class="mt-2 text-xs leading-5 text-[#7e899e]">{{ mode.description }}</p></div></div>
            <div class="mt-5 flex items-center justify-between border-t border-[#edf0f4] pt-4 text-[11px] text-[#5d52ec]"><span>{{ mode.detail }}</span><span aria-hidden="true">↗</span></div>
          </n-card>
        </div>
      </section>

      <section class="mt-7">
        <div class="mb-4 flex items-center justify-between"><h2 class="m-0 text-lg font-medium">最近项目</h2><n-button text class="!text-xs !text-[#5d52ec]" @click="navigateTo('/projects')">查看全部</n-button></div>
        <div v-if="loading" class="rounded-[14px] border border-[#dfe3eb] bg-white p-8 text-center text-xs text-[#8994a8]">正在加载项目...</div>
        <div v-else-if="!projects.length" class="rounded-[14px] border border-dashed border-[#dfe3eb] bg-white p-8 text-center text-xs text-[#8994a8]">暂无项目，先创建一个制作项目。</div>
        <div v-else class="grid grid-cols-1 gap-3 md:grid-cols-3">
          <n-card v-for="project in recentProjects" :key="project.id" hoverable class="!rounded-[12px] !border-[#dfe3eb]" :content-style="{ padding: '16px' }" @click="openProject(project.id)">
            <div class="flex items-center justify-between gap-3"><div class="flex min-w-0 items-center gap-2.5"><span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-[8px] bg-[#6654ee] text-xs font-semibold text-white">{{ project.name.slice(0, 1) }}</span><strong class="truncate text-xs font-medium">{{ project.name }}</strong></div><span class="text-[10px] text-[#929bad]">{{ project.progress }}%</span></div>
            <n-progress :percentage="project.progress" :show-indicator="false" class="mt-4" color="#6654ee" rail-color="#ececf4" type="line" />
            <p class="mt-3 truncate text-[10px] text-[#a0a8b8]">{{ project.updatedAt }} · {{ project.type }}</p>
          </n-card>
        </div>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
const message = useMessage()
const { projects, activeProject, loadProjects } = useProjectWorkspace()
const loading = ref(false)

const recentProjects = computed(() => projects.value.slice(0, 3))
const workspaceModes = [
  { title: '画布节点式', detail: '自由编排节点与连线', icon: '◇', tone: 'bg-[#eeecff] text-[#6654ee]', route: (id: string) => `/projects/${id}/canvas` },
  { title: '线性工作流式', detail: '按制作阶段逐步推进', icon: '→', tone: 'bg-[#e9f7f1] text-[#27966c]', route: (id: string) => `/projects/${id}/workflow/script` }
]

onMounted(async () => {
  loading.value = true
  try {
    await loadProjects()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '项目加载失败，当前显示本地缓存')
  } finally {
    loading.value = false
  }
})

function openProject(projectId: string) {
  navigateTo(`/projects/${projectId}`)
}
</script>
