import { storeToRefs } from 'pinia'
import { useEpisodeStore, type ProjectLibrary, type WorkspaceEpisode } from '~/stores/episode'

interface ApiEpisode {
  id: number
  episode_no: number
  title: string
  description: string | null
  status: string
  current_stage: string
  progress: number
  duration_seconds: number
  updated_at: string | null
}

interface ApiLibrary {
  characters: ProjectLibrary['characters']
  asset_summary: Record<string, number>
}

function mapEpisode(projectId: string, episode: ApiEpisode): WorkspaceEpisode {
  return {
    id: String(episode.id),
    projectId,
    episodeNo: episode.episode_no,
    title: episode.title,
    description: episode.description || '',
    status: episode.status,
    currentStage: episode.current_stage,
    progress: episode.progress,
    durationSeconds: episode.duration_seconds,
    updatedAt: episode.updated_at ? new Date(episode.updated_at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '刚刚',
  }
}

export const useEpisodeWorkspace = () => {
  const store = useEpisodeStore()
  const refs = storeToRefs(store)
  const http = useHttp()

  async function loadEpisodes(projectId: string | number) {
    const id = Number(projectId)
    if (!Number.isInteger(id) || id <= 0) {
      if (refs.episodes.value[0]?.projectId !== String(projectId)) {
        store.setEpisodes([{
          id: `${projectId}-1`, projectId: String(projectId), episodeNo: 1, title: '第001集', description: '',
          status: 'DRAFT', currentStage: 'SCRIPT', progress: 0, durationSeconds: 0, updatedAt: '刚刚',
        }])
      }
      return refs.episodes.value
    }
    try {
      const response = await http.get<{ list: ApiEpisode[] }>(`/projects/${id}/episodes`)
      const next = response.list.map((episode) => mapEpisode(String(projectId), episode))
      store.setEpisodes(next)
      return next
    } catch (error) {
      if (refs.episodes.value[0]?.projectId !== String(projectId)) {
        store.setEpisodes([{
          id: `${projectId}-1`, projectId: String(projectId), episodeNo: 1, title: '第001集', description: '',
          status: 'DRAFT', currentStage: 'SCRIPT', progress: 0, durationSeconds: 0, updatedAt: '刚刚',
        }])
      }
      throw error
    }
  }

  async function createEpisode(projectId: string | number, title: string, description = '') {
    const id = Number(projectId)
    if (!Number.isInteger(id) || id <= 0) {
      const episode: WorkspaceEpisode = {
        id: `${projectId}-${Date.now()}`,
        projectId: String(projectId),
        episodeNo: (refs.episodes.value.filter((item) => item.projectId === String(projectId)).map((item) => item.episodeNo).sort((a, b) => b - a)[0] || 0) + 1,
        title,
        description,
        status: 'DRAFT',
        currentStage: 'SCRIPT',
        progress: 0,
        durationSeconds: 0,
        updatedAt: '刚刚',
      }
      store.addEpisode(episode)
      return episode
    }
    const response = await http.post<{ id: number; episode_no: number; title: string; status: string }>(`/projects/${id}/episodes`, { title, description })
    const episode: WorkspaceEpisode = {
      id: String(response.id),
      projectId: String(projectId),
      episodeNo: response.episode_no,
      title: response.title,
      description,
      status: response.status,
      currentStage: 'SCRIPT',
      progress: 0,
      durationSeconds: 0,
      updatedAt: '刚刚',
    }
    store.addEpisode(episode)
    return episode
  }

  async function loadLibrary(projectId: string | number) {
    const id = Number(projectId)
    if (!Number.isInteger(id) || id <= 0) return refs.library.value
    const response = await http.get<ApiLibrary>(`/projects/${id}/library`)
    const next: ProjectLibrary = { characters: response.characters || [], assetSummary: response.asset_summary || {} }
    store.setLibrary(next)
    return next
  }

  return {
    ...refs,
    switchEpisode: store.switchEpisode,
    setEpisodes: store.setEpisodes,
    createEpisode,
    loadEpisodes,
    loadLibrary,
  }
}
