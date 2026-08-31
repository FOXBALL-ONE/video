<template>
  <component :is="page" :key="route.fullPath" />
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import CanvasPage from '~/pages/graph/index.vue'
import OverviewPage from '~/pages/graph/overview.vue'
import AssetsPage from '~/pages/graph/assets.vue'
import ScriptPage from '~/pages/graph/script.vue'
import TasksPage from '~/pages/graph/tasks.vue'
import ShotsPage from '~/pages/graph/shots.vue'
import VideoPage from '~/pages/graph/video.vue'

const props = defineProps<{ mode: 'overview' | 'canvas' | 'workflow' }>()
const route = useRoute()
const { switchProject, loadProjects } = useProjectWorkspace()
const { loadEpisodes, switchEpisode } = useEpisodeWorkspace()

const page = computed(() => {
  if (props.mode === 'canvas') return CanvasPage
  if (props.mode === 'workflow') {
    return ({ script: ScriptPage, tasks: TasksPage, assets: AssetsPage, shots: ShotsPage, video: VideoPage } as Record<string, typeof ScriptPage>)[String(route.params.stage)] || ScriptPage
  }
  return OverviewPage
})

async function syncProject() {
  const projectId = String(route.params.projectId || '')
  if (!projectId) return
  try {
    const projects = await loadProjects()
    if (!/^\d+$/.test(projectId) || !projects.some((project) => project.id === projectId)) {
      await navigateTo({ path: '/projects', query: { create: projects.length ? undefined : '1' } })
      return
    }
  } catch {
    return
  }
  switchProject(projectId)
  try {
    await loadEpisodes(projectId)
  } catch {
    // The sidebar keeps its local fallback when the API is unavailable.
  }
  if (route.params.episodeId) switchEpisode(String(route.params.episodeId))
}

onMounted(syncProject)
watch(() => route.params.projectId, syncProject)
</script>
