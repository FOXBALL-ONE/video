<template>
  <aside class="episode-sidebar hidden shrink-0 border-r border-[#e5e8ef] bg-[#fbfbfd] lg:block" :class="isCollapsed ? 'w-16' : 'w-[248px]'" aria-label="项目剧集导航">
    <div class="sticky top-0 flex h-[calc(100vh-65px)] flex-col">
      <div class="flex h-14 shrink-0 items-center justify-between border-b border-[#e5e8ef] px-3">
        <span v-if="!isCollapsed" class="truncate text-xs font-semibold text-[#30394a]">{{ activeProject.name }}</span>
        <button
          type="button"
          class="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg text-base text-[#737c8d] transition-colors hover:bg-[#eeecff] hover:text-[#5c50dc]"
          :aria-label="isCollapsed ? '展开剧集导航' : '收起剧集导航'"
          @click="toggleCollapsed"
        >{{ isCollapsed ? '›' : '‹' }}</button>
      </div>

      <template v-if="!isCollapsed">
        <section class="min-h-0 border-b border-[#e5e8ef] px-3 pb-3 pt-3">
          <div class="mb-2 flex items-center justify-between">
            <h2 class="m-0 text-xs font-semibold text-[#4f596d]">剧集列表 <span class="font-normal text-[#9299a7]">({{ episodes.length }})</span></h2>
            <button type="button" class="flex h-7 w-7 items-center justify-center rounded-md text-base text-[#6654ee] hover:bg-[#eeecff]" aria-label="新增剧集" @click="showCreate = !showCreate">＋</button>
          </div>
          <div v-if="showCreate" class="mb-2 flex gap-1.5">
            <input v-model="newTitle" class="h-8 min-w-0 flex-1 rounded-md border border-[#dfe2e8] bg-white px-2 text-xs outline-none focus:border-[#9b91f5]" placeholder="剧集名称" @keyup.enter="submitEpisode" />
            <button type="button" class="h-8 rounded-md bg-[#6654ee] px-2 text-xs text-white disabled:opacity-50" :disabled="!newTitle.trim() || creating" @click="submitEpisode">{{ creating ? '...' : '添加' }}</button>
          </div>
          <div class="max-h-[29vh] space-y-0.5 overflow-y-auto pr-0.5">
            <button type="button" class="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-xs text-[#7a8496] transition-colors hover:bg-[#f0effb]" @click="openOverview">
              <span class="text-sm">☆</span><span>全局视图</span>
            </button>
            <button
              v-for="episode in episodes"
              :key="episode.id"
              type="button"
              class="w-full rounded-lg px-2.5 py-2 text-left transition-colors"
              :class="episode.id === activeEpisode?.id ? 'bg-[#eeecff] text-[#574adb]' : 'text-[#4f596d] hover:bg-[#f0effb]'"
              @click="openEpisode(episode.id)"
            >
              <span class="flex items-center justify-between gap-2">
                <strong class="truncate text-xs font-medium">{{ episode.title }}</strong>
                <span class="shrink-0 text-[10px] text-[#9299a7]">{{ formatDuration(episode.durationSeconds) }}</span>
              </span>
              <span class="mt-1 block truncate text-[10px] text-[#9299a7]">{{ episode.description || `${episode.progress}% 完成` }}</span>
            </button>
          </div>
        </section>

        <section class="min-h-0 flex-1 overflow-y-auto border-b border-[#e5e8ef] px-3 py-3">
          <div class="mb-1 flex items-center justify-between">
            <h2 class="m-0 text-xs font-semibold text-[#4f596d]">角色库 <span class="font-normal text-[#9299a7]">({{ library.characters.length }})</span></h2>
            <span class="text-[10px] text-[#9299a7]">全项目</span>
          </div>
          <p v-if="!library.characters.length" class="m-0 pt-1 text-[11px] leading-5 text-[#9299a7]">解析剧本后自动生成</p>
          <div v-else class="mt-2 space-y-1">
            <div v-for="character in library.characters" :key="character.name" class="flex items-center gap-2 rounded-md px-1.5 py-1.5">
              <span class="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-[#e9e7ff] text-xs font-semibold text-[#5d50dd]">{{ character.name.slice(0, 1) }}</span>
              <div class="min-w-0"><strong class="block truncate text-xs font-medium text-[#4f596d]">{{ character.name }}</strong><span class="block truncate text-[10px] text-[#9299a7]">{{ character.description || '暂无人物设定' }}</span></div>
            </div>
          </div>
        </section>

        <section class="shrink-0 px-3 py-3" aria-label="项目资产总结">
          <h2 class="m-0 mb-2 text-xs font-semibold text-[#4f596d]">项目资产总结</h2>
          <div class="grid grid-cols-2 gap-x-3 gap-y-1.5 text-[11px]">
            <div class="flex justify-between text-[#7f899b]"><span>人物</span><strong class="text-[#30394a]">{{ count('character') }}</strong></div>
            <div class="flex justify-between text-[#7f899b]"><span>场景背景</span><strong class="text-[#30394a]">{{ count('scene_background') }}</strong></div>
            <div class="flex justify-between text-[#7f899b]"><span>道具</span><strong class="text-[#30394a]">{{ count('prop') }}</strong></div>
            <div class="flex justify-between text-[#7f899b]"><span>服饰</span><strong class="text-[#30394a]">{{ count('costume') }}</strong></div>
            <div class="flex justify-between text-[#7f899b]"><span>声音</span><strong class="text-[#30394a]">{{ count('voice') + count('music') + count('sound_effect') }}</strong></div>
          </div>
          <div class="mt-2 flex items-center justify-between border-t border-[#e8eaf0] pt-2 text-[10px] text-[#9299a7]"><span>全部资产</span><strong class="text-[#5c50dc]">{{ count('total') }}</strong></div>
        </section>
      </template>

      <div v-else class="flex flex-col items-center gap-3 pt-4">
        <span class="text-lg text-[#6654ee]" aria-hidden="true">✦</span>
        <span class="h-px w-7 bg-[#e5e8ef]"></span>
        <button
          v-for="episode in episodes"
          :key="episode.id"
          type="button"
          class="flex h-7 w-7 items-center justify-center rounded-md text-[10px] text-[#737c8d] hover:bg-[#eeecff] hover:text-[#5c50dc]"
          :class="episode.id === activeEpisode?.id ? 'bg-[#eeecff] font-semibold text-[#574adb]' : ''"
          :title="episode.title"
          @click="openEpisode(episode.id)"
        >{{ String(episode.episodeNo).padStart(2, '0') }}</button>
        <span class="mt-1 text-[10px] text-[#9299a7]" :title="`角色 ${library.characters.length} 个`">{{ library.characters.length }}</span>
      </div>
    </div>
  </aside>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useEpisodeWorkspace } from '~/composables/useEpisodeWorkspace'
import { useProjectWorkspace } from '~/composables/useProjectWorkspace'

const route = useRoute()
const message = useMessage()
const { activeProject } = useProjectWorkspace()
const { episodes, activeEpisode, library, switchEpisode, loadEpisodes, loadLibrary, createEpisode } = useEpisodeWorkspace()
const isCollapsed = ref(false)
const showCreate = ref(false)
const creating = ref(false)
const newTitle = ref('')

function projectId() {
  return String(route.params.projectId || activeProject.value?.id || '')
}

function toggleCollapsed() {
  isCollapsed.value = !isCollapsed.value
  if (import.meta.client) localStorage.setItem('video-episode-sidebar-collapsed', String(isCollapsed.value))
}

function formatDuration(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function count(key: string) {
  return library.value.assetSummary[key] || 0
}

function currentStage() {
  const stage = String(route.params.stage || 'script')
  return ['script', 'tasks', 'assets', 'shots', 'video'].includes(stage) ? stage : 'script'
}

function openEpisode(id: string) {
  switchEpisode(id)
  navigateTo(`/projects/${projectId()}/episodes/${id}/workflow/${currentStage()}`)
}

function openOverview() {
  navigateTo(`/projects/${projectId()}`)
}

async function submitEpisode() {
  const title = newTitle.value.trim()
  if (!title || creating.value) return
  creating.value = true
  try {
    const episode = await createEpisode(projectId(), title)
    newTitle.value = ''
    showCreate.value = false
    navigateTo(`/projects/${projectId()}/episodes/${episode.id}/workflow/script`)
    message.success('剧集已创建')
  } catch {
    message.error('剧集创建失败，请确认项目已连接服务器')
  } finally {
    creating.value = false
  }
}

async function loadNavigation() {
  const id = projectId()
  try {
    await Promise.all([loadEpisodes(id), loadLibrary(id)])
  } catch {
    // Keep the local navigation data when the API is unavailable.
  }
  const routeEpisode = String(route.params.episodeId || '')
  if (routeEpisode) switchEpisode(routeEpisode)
}

onMounted(() => {
  if (import.meta.client) isCollapsed.value = localStorage.getItem('video-episode-sidebar-collapsed') === 'true'
  loadNavigation()
})

watch(() => route.params.projectId, (next, previous) => {
  if (next && next !== previous) loadNavigation()
})

watch(() => route.params.episodeId, (next) => {
  if (next) switchEpisode(String(next))
})
</script>
