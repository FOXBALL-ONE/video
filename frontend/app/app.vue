<template>
  <NMessageProvider>
    <div class="min-h-screen bg-[#f5f6fa]">
      <div v-if="route.path !== '/login'" class="border-b border-[#e5e8ef] bg-white/90 px-4 py-2 backdrop-blur sm:px-6 lg:px-8">
        <div class="mx-auto flex min-h-[48px] max-w-[1177px] items-center justify-between gap-4">
          <div class="flex min-w-0 items-center gap-3">
            <NuxtLink to="/" class="hidden shrink-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee] sm:block">VIDEO WORKSPACE</NuxtLink>
            <span class="hidden h-5 w-px bg-[#e5e8ef] sm:block" aria-hidden="true"></span>
            <ProjectSwitcher />
          </div>
          <nav class="flex min-w-0 items-center gap-2 overflow-x-auto text-xs text-[#8994a8]" aria-label="工作区导航">
            <NuxtLink :to="canvasRoute" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">画布工作台</NuxtLink>
            <NuxtLink :to="workflowRoute" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">线性工作流</NuxtLink>
            <NuxtLink :to="taskRoute" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">任务中心</NuxtLink>
            <NuxtLink to="/tasks" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">我的任务</NuxtLink>
            <NuxtLink to="/projects" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">项目管理</NuxtLink>
            <NuxtLink to="/api" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">API 管理</NuxtLink>
            <NuxtLink to="/templates" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">模板目录</NuxtLink>
            <n-dropdown trigger="click" :options="accountOptions" @select="handleAccountAction">
              <button type="button" class="flex items-center gap-2 rounded-lg px-2 py-1.5 text-left hover:bg-[#f0eeff]">
                <span class="flex h-7 w-7 items-center justify-center rounded-[8px] bg-[#6654ee] text-[11px] font-semibold text-white">{{ user?.username?.slice(0, 1).toUpperCase() || 'U' }}</span>
                <span class="hidden max-w-[110px] truncate text-[11px] font-medium text-[#4d5870] sm:inline">{{ user?.username || '当前用户' }}</span>
              </button>
            </n-dropdown>
          </nav>
        </div>
      </div>
      <div class="flex min-w-0">
        <EpisodeSidebar v-if="showEpisodeSidebar" />
        <div class="min-w-0 flex-1">
          <NuxtPage />
        </div>
      </div>
    </div>
  </NMessageProvider>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useAuth } from '~/composables/useAuth'
import { useProjectWorkspace } from '~/composables/useProjectWorkspace'
import { useEpisodeWorkspace } from '~/composables/useEpisodeWorkspace'

const route = useRoute();
const {user, token, logout} = useAuth();
const { activeProject, loadProjects } = useProjectWorkspace()
const { activeEpisodeId } = useEpisodeWorkspace()
const canvasRoute = computed(() => {
  if (!activeProject.value?.id) return '/projects'
  const episodeId = /^\d+$/.test(String(activeEpisodeId.value || '')) ? activeEpisodeId.value : null
  return episodeId
    ? `/projects/${activeProject.value.id}/episodes/${episodeId}/canvas`
    : `/projects/${activeProject.value.id}/canvas`
})
const workflowRoute = computed(() => {
  if (!activeProject.value?.id) return '/projects'
  const episodeId = /^\d+$/.test(String(activeEpisodeId.value || '')) ? activeEpisodeId.value : null
  return episodeId
    ? `/projects/${activeProject.value.id}/episodes/${episodeId}/workflow/script`
    : `/projects/${activeProject.value.id}/workflow/script`
})
const taskRoute = computed(() => activeProject.value?.id ? `/projects/${activeProject.value.id}/tasks` : '/projects')
const showEpisodeSidebar = computed(() => {
  if (route.path === '/login' || !activeProject.value || !/^\/projects\/\d+/.test(route.path)) return false
  return !route.path.endsWith('/canvas')
})
const accountOptions = [{label: '退出登录', key: 'logout'}];

async function refreshProjects() {
  if (route.path === '/login' || !token.value) return
  try {
    await loadProjects()
  } catch {
    // Individual pages show a detailed load error and can retry independently.
  }
}

onMounted(refreshProjects)
watch(token, (value) => {
  if (value) refreshProjects()
})

async function handleAccountAction(key: string) {
  if (key !== 'logout') return;
  await logout();
  await navigateTo('/login');
}
</script>
