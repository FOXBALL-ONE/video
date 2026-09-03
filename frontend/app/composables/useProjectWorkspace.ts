import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '~/stores/workspace'
import type { ApiProject, ApiProjectDetail, WorkspaceProject } from '~/stores/workspace'

function mapProject(project: ApiProject): WorkspaceProject {
  const duration = `${Math.floor(project.duration_seconds / 60)}:${String(project.duration_seconds % 60).padStart(2, '0')}`
  return {
    id: String(project.id), code: project.code, name: project.name, type: project.type, description: project.description,
    status: project.status === 'DRAFT' ? '草稿' : project.status === 'IN_PRODUCTION' ? '制作中' : project.status === 'COMPLETED' ? '已完成' : project.status === 'ARCHIVED' ? '已归档' : project.status,
    progress: project.progress, version: project.version, duration, score: project.quality_score == null ? '--' : String(project.quality_score),
    updatedAt: project.updated_at ? new Date(project.updated_at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) : '刚刚',
    editor: project.editor || '当前用户', pending: `${project.pending_count} 项待处理`, pendingDetail: project.pending_detail || '', activities: [],
  }
}

export const useProjectWorkspace = () => {
  const store = useWorkspaceStore()
  const refs = storeToRefs(store)
  const http = useHttp()

  async function loadProjects() {
    const response = await http.get<{ list: ApiProject[] }>('/projects')
    const next = (Array.isArray(response) ? response : response?.list || []).map(mapProject)
    store.setProjects(next)
    return next
  }

  async function createProject(input: { name: string; type: string; description?: string; editor?: string }) {
    const project = await http.post<ApiProject>('/projects', {
      name: input.name, type: input.type, description: input.description || '', editor: input.editor,
    })
    const refreshed = await loadProjects()
    const mapped = refreshed.find((item) => item.id === String(project.id))
    if (!mapped) throw new Error('项目已提交，但未能从服务器读取到保存结果')
    store.switchProject(mapped.id)
    return mapped
  }

  async function getProject(id: string | number) {
    return http.get<ApiProjectDetail>(`/projects/${Number(id)}`)
  }

  async function updateProject(id: string | number, input: {
    name: string
    type: string
    description: string
    version: string
    duration_seconds: number
    quality_score?: number | null
    editor?: string
    pending_count: number
    pending_detail?: string
  }) {
    await http.put(`/projects/${Number(id)}`, {
      ...input,
      quality_score: input.quality_score == null ? undefined : input.quality_score,
      editor: input.editor || undefined,
      pending_detail: input.pending_detail || undefined,
    })
    const refreshed = await loadProjects()
    return refreshed.find((project) => project.id === String(id)) || null
  }

  async function deleteProject(id: string | number) {
    await http.delete(`/projects/${Number(id)}`)
    await loadProjects()
  }

  return {
    ...refs,
    switchProject: store.switchProject,
    loadProjects,
    createProject,
    getProject,
    updateProject,
    deleteProject,
  }
}
