import { storeToRefs } from 'pinia'
import { useWorkspaceStore } from '~/stores/workspace'

export const useProjectWorkspace = () => {
  const store = useWorkspaceStore()
  const refs = storeToRefs(store)

  return {
    ...refs,
    switchProject: store.switchProject,
    createProject: store.createProject,
  }
}
