import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '~/stores/workspace'
import type { ApiProject, WorkspaceProject } from '~/stores/workspace'

function mapProject(project: ApiProject): WorkspaceProject {
  const duration = `${Math.floor(project.duration_seconds / 60)}:${String(project.duration_seconds % 60).padStart(2, '0')}`
  return {
    id: String(project.id), code: project.code, name: project.name, type: project.type, description: project.description,
    status: project.status === 'DRAFT' ? '草稿' : project.status === 'IN_PRODUCTION' ? '制作中' : project.status,
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
    const next = response.list.map(mapProject)
    store.setProjects(next)
    return next
  }

  async function createProject(input: { name: string; type: string; description?: string; editor?: string }) {
    const project = await http.post<ApiProject>('/projects', {
      name: input.name, type: input.type, description: input.description || '', editor: input.editor,
    })
    const mapped = mapProject(project)
    store.addProject(mapped)
    return mapped
  }

  return {
    ...refs,
    switchProject: store.switchProject,
    loadProjects,
    createProject,
  }
}
