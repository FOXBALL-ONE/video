<template>
  <n-dropdown trigger="click" placement="bottom-start" :options="dropdownOptions" @select="handleSelect">
    <button type="button" class="flex min-w-0 items-center gap-2 rounded-[10px] px-2 py-1.5 text-left transition-colors hover:bg-[#f0eeff] focus:outline-none focus:ring-2 focus:ring-[#8f87ff]">
      <span class="flex h-8 w-8 shrink-0 items-center justify-center rounded-[9px] bg-[#6654ee] text-sm font-semibold text-white">{{ activeProject?.name?.slice(0, 1) || '+' }}</span>
      <span class="flex min-w-0 flex-col">
        <span class="text-[10px] leading-4 text-[#929bad]">当前项目</span>
        <strong class="max-w-[150px] truncate text-xs font-semibold text-[#303b51] sm:max-w-[210px]">{{ activeProject?.name || '创建项目' }}</strong>
      </span>
      <span class="ml-1 text-xs text-[#8994a8]" aria-hidden="true">⌄</span>
    </button>
  </n-dropdown>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const route = useRoute()
const { projects, activeProject, switchProject } = useProjectWorkspace()

const dropdownOptions = computed(() => [
  {
    type: 'group',
    label: '切换项目',
    key: 'project-group',
    children: projects.value.map((project) => ({
      label: `${project.name} · ${project.type}`,
      key: `project:${project.id}`
    }))
  },
  { type: 'divider', key: 'project-divider' },
  { label: '项目管理', key: 'manage-projects' },
  { label: '新建项目', key: 'create-project' }
])

function handleSelect(key: string) {
  if (key.startsWith('project:')) {
    const projectId = key.slice('project:'.length)
    switchProject(projectId)
    if (route.path.startsWith('/projects/')) navigateTo(`/projects/${projectId}`)
    else navigateTo(`/projects/${projectId}/canvas`)
    return
  }
  if (key === 'manage-projects') navigateTo('/projects')
  if (key === 'create-project') navigateTo({ path: '/projects', query: { create: '1' } })
}
</script>
