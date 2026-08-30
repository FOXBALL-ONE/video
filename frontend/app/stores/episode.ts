import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

export interface WorkspaceEpisode {
  id: string
  projectId: string
  episodeNo: number
  title: string
  description: string
  status: string
  currentStage: string
  progress: number
  durationSeconds: number
  updatedAt: string
}

export interface ProjectCharacter {
  name: string
  description: string | null
}

export interface ProjectLibrary {
  characters: ProjectCharacter[]
  assetSummary: Record<string, number>
}

const fallbackEpisodes: WorkspaceEpisode[] = [
  {
    id: 'rain-letter-1',
    projectId: 'rain-letter',
    episodeNo: 1,
    title: '第001集',
    description: '林夏在雨夜收到来自十年前自己的信。',
    status: 'IN_PRODUCTION',
    currentStage: 'SCRIPT',
    progress: 68,
    durationSeconds: 156,
    updatedAt: '今天 14:32',
  },
]

function cloneEpisodes() {
  return fallbackEpisodes.map((episode) => ({ ...episode }))
}

export const useEpisodeStore = defineStore('episode', () => {
  const episodes = ref<WorkspaceEpisode[]>(cloneEpisodes())
  const activeEpisodeId = ref<string | null>(fallbackEpisodes[0].id)
  const library = ref<ProjectLibrary>({
    characters: [{ name: '林夏', description: '失意摄影师，正在追查信件来源。' }],
    assetSummary: { character: 1, scene: 1, prop: 1, voice: 0, music: 0, sound_effect: 0, total: 3 },
  })

  const activeEpisode = computed(() => episodes.value.find((episode) => episode.id === activeEpisodeId.value) || episodes.value[0] || null)

  function setEpisodes(next: WorkspaceEpisode[]) {
    episodes.value = next
    if (!next.some((episode) => episode.id === activeEpisodeId.value)) activeEpisodeId.value = next[0]?.id || null
  }

  function switchEpisode(id: string) {
    if (episodes.value.some((episode) => episode.id === id)) activeEpisodeId.value = id
  }

  function setLibrary(next: ProjectLibrary) {
    library.value = next
  }

  function addEpisode(episode: WorkspaceEpisode) {
    episodes.value = [...episodes.value.filter((item) => item.id !== episode.id), episode].sort((a, b) => a.episodeNo - b.episodeNo)
    activeEpisodeId.value = episode.id
  }

  return { episodes, activeEpisodeId, activeEpisode, library, setEpisodes, switchEpisode, setLibrary, addEpisode }
})
