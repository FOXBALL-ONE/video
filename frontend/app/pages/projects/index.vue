<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-6 border-b border-[#e5e8ef] pb-8 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">WORKSPACE · PROJECTS</p>
          <h1 class="mt-5 text-[31px] font-medium leading-[1.18]">项目管理</h1>
          <p class="mt-3 max-w-[620px] text-[13px] leading-6 text-[#8a94aa]">
            每个项目都是独立的制作工作区，剧本、资产、分镜和视频任务都会跟随当前项目保存。</p>
        </div>
        <n-button :loading="loading" class="!h-[42px] !rounded-[10px]" color="#6654ee" type="primary"
                  @click="showCreate = !showCreate">{{ showCreate ? '收起表单' : '新建项目' }}
        </n-button>
      </header>

      <section v-if="showCreate"
               class="mt-6 rounded-[15px] border border-[#d8d2ff] bg-white p-5 shadow-[0_8px_24px_rgba(91,79,235,0.06)] sm:p-6">
        <div class="flex items-center justify-between gap-4">
          <div><h2 class="m-0 text-base font-medium">创建制作项目</h2>
            <p class="mt-1.5 text-xs text-[#8994a8]">创建后会自动进入该项目的总览页面。</p></div>
          <span class="text-[11px] text-[#929bad]">必填项目名称</span>
        </div>
        <n-form class="mt-5 grid grid-cols-1 gap-3 md:grid-cols-[1fr_220px]" @submit.prevent="submitCreate">
          <n-form-item label="项目名称" required>
            <n-input v-model:value="form.name" placeholder="例如：春日漫游"/>
          </n-form-item>
          <n-form-item label="项目类型">
            <n-select v-model:value="form.type" :options="typeOptions"/>
          </n-form-item>
          <n-form-item class="md:col-span-2" label="项目简介">
            <n-input v-model:value="form.description" :rows="2" placeholder="一句话描述这个项目的故事或制作目标"
                     type="textarea"/>
          </n-form-item>
          <div class="flex justify-end gap-2 md:col-span-2">
            <n-button :disabled="loading" attr-type="button" secondary @click="cancelCreate">取消</n-button>
            <n-button :loading="loading" attr-type="submit" color="#6654ee" type="primary">创建并进入项目</n-button>
          </div>
        </n-form>
      </section>

      <section v-if="projects.length" class="mt-7">
        <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <h2 class="m-0 text-lg font-medium">制作流程</h2>
            <p class="mt-1.5 text-xs text-[#8994a8]">选择一个项目，从剧本输入开始推进完整制作流程。</p>
          </div>
          <n-select v-model:value="activeProjectId" :options="projectOptions" class="w-full sm:!w-[220px]"
                    placeholder="选择项目" size="small"/>
        </div>
        <div class="rounded-[15px] border border-[#dfe3eb] bg-white p-4 shadow-[0_6px_18px_rgba(35,43,66,0.03)] sm:p-5">
          <div class="workflow-nodes">
            <template v-for="(step, index) in workflowSteps" :key="step.id">
              <span v-if="index" aria-hidden="true" class="workflow-connector"></span>
              <NuxtLink :to="step.route" class="workflow-node group"
                        @click="selectWorkflowProject">
                <span :class="step.iconClass" aria-hidden="true" class="workflow-node-icon">{{ step.icon }}</span>
                <span class="mt-3 text-xs font-semibold text-[#303b51] transition-colors group-hover:text-[#594df0]">{{
                    step.title
                  }}</span>
                <span class="mt-1 text-[10px] leading-4 text-[#929bad]">{{ step.caption }}</span>
                <span class="mt-3 inline-flex items-center gap-1 text-[10px] font-medium text-[#6654ee]">{{
                    step.action
                  }} <span aria-hidden="true">↗</span></span>
              </NuxtLink>
            </template>
          </div>
        </div>
      </section>

      <section class="mt-7">
        <div class="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h2 class="m-0 text-lg font-medium">我的项目</h2>
            <span class="mt-1 block text-xs text-[#929bad]">{{ filteredProjects.length }} / {{
                projects.length
              }} 个项目</span>
          </div>
          <div class="flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
            <n-input v-model:value="searchKeyword" class="sm:!w-[220px]" clearable
                     placeholder="搜索项目名称、编号或类型">
              <template #prefix><span aria-hidden="true" class="text-[#929bad]">⌕</span></template>
            </n-input>
            <n-select v-model:value="statusFilter" :options="statusOptions" class="sm:!w-[130px]" clearable
                      placeholder="全部状态"/>
          </div>
        </div>
        <div v-if="loading && !projects.length"
             class="rounded-[15px] border border-[#dfe3eb] bg-white p-10 text-center text-xs text-[#8994a8]">正在加载项目...
        </div>
        <div v-else-if="!projects.length"
             class="rounded-[15px] border border-dashed border-[#dfe3eb] bg-white p-10 text-center text-xs text-[#8994a8]">
          暂无项目，点击右上角新建项目。
        </div>
        <div v-else-if="!filteredProjects.length"
             class="rounded-[15px] border border-dashed border-[#dfe3eb] bg-white p-10 text-center text-xs text-[#8994a8]">
          没有匹配的项目，请调整搜索条件。
        </div>
        <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          <n-card v-for="project in filteredProjects" :key="project.id"
                  :class="project.id === activeProjectId ? 'project-card-active' : ''"
                  :content-style="{ padding: '20px' }"
                  class="project-card group !rounded-[16px] !border-[#dfe3eb]">
            <div class="flex items-start justify-between gap-3">
              <div class="flex min-w-0 items-center gap-3">
                <span
                    class="flex h-11 w-11 shrink-0 items-center justify-center rounded-[12px] bg-gradient-to-br from-[#7b6cff] via-[#6654ee] to-[#4a3ad6] text-[17px] font-semibold text-white shadow-[0_6px_14px_rgba(102,84,238,0.26)]">{{
                    project.name.slice(0, 1)
                  }}</span>
                <div class="min-w-0">
                  <h3 class="m-0 truncate text-[15px] font-semibold leading-6">{{ project.name }}</h3>
                  <span
                      class="mt-1.5 inline-block max-w-full truncate rounded-full border border-[#e4e7ee] bg-[#fbfcfe] px-2 py-px text-[10px] leading-4 text-[#8a94aa]">{{
                      project.type
                    }}</span>
                </div>
              </div>
              <div class="flex shrink-0 flex-col items-end gap-1.5">
                <n-tag :bordered="false"
                       :type="project.status === '草稿' || project.status === '已归档' ? 'default' : project.status === '制作中' ? 'warning' : 'success'"
                       class="!rounded-md" size="small">
                  {{ project.status }}
                </n-tag>
                <n-tag v-if="project.id === activeProjectId" :bordered="false" class="!rounded-full !text-[10px] !text-white" color="#6654ee"
                       size="small">当前项目
                </n-tag>
              </div>
            </div>
            <p class="mt-4 line-clamp-2 min-h-[40px] text-xs leading-5 text-[#687388]">{{ project.description }}</p>
            <div class="mt-4">
              <div class="flex items-center justify-between text-[11px] text-[#8994a8]"><span>制作完成度</span><strong
                  class="font-semibold text-[#5d52ec]">{{ project.progress }}%</strong></div>
              <n-progress :height="8" :percentage="project.progress" :show-indicator="false" class="mt-2.5"
                          color="linear-gradient(90deg, #8578ff, #5b4ff0)" rail-color="#eef0f5" type="line"/>
            </div>
            <div class="mt-4 grid grid-cols-3 gap-2">
              <div
                  v-for="[label, value] in [['版本', project.version], ['时长', project.duration], ['评分', project.score]]"
                  :key="label" class="rounded-[10px] bg-[#f7f8fb] px-3 py-2">
                <span class="block text-[10px] text-[#9aa3b5]">{{ label }}</span>
                <strong class="mt-0.5 block truncate text-[11px] font-semibold text-[#4b566b]">{{ value }}</strong>
              </div>
            </div>
            <div class="mt-4 flex items-center justify-between gap-2 border-t border-[#eef0f4] pt-3.5">
              <span class="min-w-0 truncate text-[10px] text-[#a0a8b8]">更新于 {{ project.updatedAt }}</span>
              <div class="flex shrink-0 items-center gap-1.5">
                <n-button class="!h-7 !rounded-[8px] !border-[#dfe2e8] !px-2.5 !text-[11px] !text-[#687388]" ghost
                          size="small" @click="openEdit(project)">编辑
                </n-button>
                <n-popconfirm negative-text="取消" positive-text="删除" @positive-click="removeProject(project)">
                  <template #trigger>
                    <n-button class="!h-7 !rounded-[8px] !border-[#f0d9d9] !px-2.5 !text-[11px] !text-[#b85c5c]" ghost
                              size="small">删除
                    </n-button>
                  </template>
                  确定删除「{{ project.name }}」吗？删除后项目会从列表中隐藏，可通过后台恢复。
                </n-popconfirm>
                <n-button class="!h-7 !rounded-[8px] !border-[#d8d2ff] !px-2.5 !text-[11px] !text-[#5d52ec]" ghost
                          size="small" @click="openProject(project)">
                  {{ project.id === activeProjectId ? '进入工作区' : '切换并进入' }}
                  <span aria-hidden="true"
                        class="inline-block transition-transform duration-150 group-hover:-translate-y-0.5 group-hover:translate-x-0.5">↗</span>
                </n-button>
              </div>
            </div>
          </n-card>
        </div>
      </section>
    </div>

    <n-modal v-model:show="showEdit" :mask-closable="!saving" class="w-[min(94vw,620px)]" preset="card"
             title="编辑项目资料">
      <n-spin :show="detailLoading">
        <n-form label-placement="top" @submit.prevent="submitEdit">
          <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <n-form-item label="项目名称" required>
              <n-input v-model:value="editForm.name" maxlength="100" placeholder="项目名称"/>
            </n-form-item>
            <n-form-item label="项目类型" required>
              <n-select v-model:value="editForm.type" :options="typeOptions"/>
            </n-form-item>
          </div>
          <n-form-item label="项目简介">
            <n-input v-model:value="editForm.description" :rows="3" maxlength="1000" placeholder="项目故事或制作目标"
                     type="textarea"/>
          </n-form-item>
          <div class="grid grid-cols-1 gap-3 sm:grid-cols-3">
            <n-form-item label="版本号" required>
              <n-input v-model:value="editForm.version" placeholder="v0.1"/>
            </n-form-item>
            <n-form-item label="预计时长（秒）" required>
              <n-input-number v-model:value="editForm.duration_seconds" :max="86400" :min="0" class="w-full"/>
            </n-form-item>
            <n-form-item label="质量评分">
              <n-input-number v-model:value="editForm.quality_score" :max="10" :min="0" :step="0.1" class="w-full"
                              clearable/>
            </n-form-item>
          </div>
          <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <n-form-item label="负责人">
              <n-input v-model:value="editForm.editor" maxlength="100" placeholder="可选"/>
            </n-form-item>
            <n-form-item label="待处理事项数量" required>
              <n-input-number v-model:value="editForm.pending_count" :min="0" class="w-full"/>
            </n-form-item>
          </div>
          <n-form-item label="待处理事项说明">
            <n-input v-model:value="editForm.pending_detail" :rows="2" maxlength="1000" placeholder="可选"
                     type="textarea"/>
          </n-form-item>
          <div class="flex justify-end gap-2">
            <n-button :disabled="saving" attr-type="button" secondary @click="showEdit = false">取消</n-button>
            <n-button :disabled="detailLoading" :loading="saving" attr-type="submit" color="#6654ee" type="primary">
              保存修改
            </n-button>
          </div>
        </n-form>
      </n-spin>
    </n-modal>
  </main>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'
import type {ApiProjectDetail, WorkspaceProject} from '~/stores/workspace'

const route = useRoute()
const message = useMessage()
const {
  projects,
  activeProjectId,
  switchProject,
  loadProjects,
  createProject,
  getProject,
  updateProject,
  deleteProject
} = useProjectWorkspace()
const showCreate = ref(route.query.create === '1')
const form = ref({name: '', type: '悬疑短片', description: ''})
const loading = ref(false)
const saving = ref(false)
const detailLoading = ref(false)
const showEdit = ref(false)
const searchKeyword = ref('')
const statusFilter = ref<string | null>(null)
const editingProjectId = ref<string | null>(null)
const editForm = ref({
  name: '', type: '悬疑短片', description: '', version: 'v0.1', duration_seconds: 0,
  quality_score: null as number | null, editor: '', pending_count: 0, pending_detail: '',
})
const typeOptions = [
  {label: '悬疑短片', value: '悬疑短片'},
  {label: '纪录短片', value: '纪录短片'},
  {label: '科幻概念片', value: '科幻概念片'},
  {label: '品牌宣传片', value: '品牌宣传片'}
]
const statusOptions = [
  {label: '草稿', value: '草稿'},
  {label: '制作中', value: '制作中'},
  {label: '已完成', value: '已完成'},
  {label: '已归档', value: '已归档'},
]

const projectOptions = computed(() => projects.value.map((project) => ({label: project.name, value: project.id})))
const filteredProjects = computed(() => {
  const keyword = searchKeyword.value.trim().toLocaleLowerCase()
  return projects.value.filter((project) => {
    const matchesStatus = !statusFilter.value || project.status === statusFilter.value
    const matchesKeyword = !keyword || [project.name, project.code, project.type, project.description]
        .some((value) => value.toLocaleLowerCase().includes(keyword))
    return matchesStatus && matchesKeyword
  })
})
const workflowSteps = computed(() => {
  const projectId = String(activeProjectId.value || '')
  const base = `/projects/${projectId}/workflow`
  return [
    {
      id: 'script',
      title: '剧本导入',
      caption: '故事结构与对白',
      action: '开始输入',
      icon: '✦',
      iconClass: 'bg-[#eeecff] text-[#6654ee]',
      route: `${base}/script`
    },
    {
      id: 'tasks',
      title: '每集任务卡',
      caption: '目标与制作约束',
      action: '配置任务',
      icon: '▤',
      iconClass: 'bg-[#fff4e5] text-[#e59a37]',
      route: `${base}/tasks`
    },
    {
      id: 'assets',
      title: '资产管理',
      caption: '角色、场景与道具',
      action: '整理资产',
      icon: '◇',
      iconClass: 'bg-[#e9f7f1] text-[#27966c]',
      route: `${base}/assets`
    },
    {
      id: 'shots',
      title: '分镜规划',
      caption: '景别、运镜与连续性',
      action: '规划分镜',
      icon: '▦',
      iconClass: 'bg-[#edf4ff] text-[#4285df]',
      route: `${base}/shots`
    },
    {
      id: 'video',
      title: '画面视频',
      caption: '画面、配音与合成',
      action: '制作视频',
      icon: '▶',
      iconClass: 'bg-[#f4edff] text-[#9a67d6]',
      route: `${base}/video`
    }
  ]
})

onMounted(async () => {
  loading.value = true
  try {
    await loadProjects()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '项目列表加载失败，当前显示本地缓存')
  } finally {
    loading.value = false
  }
})

async function submitCreate() {
  const name = form.value.name.trim()
  if (!name) {
    message.warning('请先填写项目名称')
    return
  }
  loading.value = true
  try {
    const project = await createProject({name, type: form.value.type, description: form.value.description.trim()})
    message.success('项目已创建')
    await navigateTo(`/projects/${project.id}`)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '项目创建失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

function cancelCreate() {
  showCreate.value = false
  form.value = {name: '', type: '悬疑短片', description: ''}
}

function openProject(project: { id: string }) {
  switchProject(project.id)
  navigateTo(`/projects/${project.id}`)
}

async function openEdit(project: WorkspaceProject) {
  editingProjectId.value = project.id
  showEdit.value = true
  detailLoading.value = true
  try {
    const detail: ApiProjectDetail = await getProject(project.id)
    editForm.value = {
      name: detail.name,
      type: detail.type,
      description: detail.description,
      version: detail.version,
      duration_seconds: detail.duration_seconds,
      quality_score: detail.quality_score,
      editor: detail.editor || '',
      pending_count: detail.pending_count,
      pending_detail: detail.pending_detail || '',
    }
  } catch (error) {
    showEdit.value = false
    message.error(error instanceof Error ? error.message : '项目详情加载失败')
  } finally {
    detailLoading.value = false
  }
}

async function submitEdit() {
  const id = editingProjectId.value
  const name = editForm.value.name.trim()
  if (!id || !name) {
    message.warning('请填写项目名称')
    return
  }
  if (!/^v\d+\.\d+$/.test(editForm.value.version.trim())) {
    message.warning('版本号格式应为 v数字.数字，例如 v1.0')
    return
  }
  saving.value = true
  try {
    await updateProject(id, {
      ...editForm.value,
      name,
      version: editForm.value.version.trim(),
      description: editForm.value.description.trim(),
      editor: editForm.value.editor.trim(),
      pending_detail: editForm.value.pending_detail.trim(),
    })
    showEdit.value = false
    message.success('项目资料已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '项目更新失败')
  } finally {
    saving.value = false
  }
}

async function removeProject(project: WorkspaceProject) {
  saving.value = true
  try {
    await deleteProject(project.id)
    message.success('项目已删除')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '项目删除失败')
  } finally {
    saving.value = false
  }
}

function selectWorkflowProject() {
  if (activeProjectId.value) switchProject(String(activeProjectId.value))
}
</script>

<style scoped>
.workflow-nodes {
  display: flex;
  align-items: stretch;
  gap: 10px;
  overflow-x: auto;
  padding: 2px;
  scrollbar-width: thin;
}

.workflow-node {
  display: flex;
  min-width: 148px;
  flex: 1 0 148px;
  flex-direction: column;
  align-items: flex-start;
  border: 1px solid #e2e5ec;
  border-radius: 12px;
  padding: 14px;
  text-decoration: none;
  transition: transform 160ms ease, border-color 160ms ease, box-shadow 160ms ease;
}

.workflow-node:hover {
  transform: translateY(-2px);
  border-color: #776cff;
  box-shadow: 0 10px 22px rgba(91, 79, 235, 0.1);
}

.workflow-node:focus-visible {
  outline: 2px solid #8f87ff;
  outline-offset: 2px;
}

.workflow-node-icon {
  display: flex;
  height: 34px;
  width: 34px;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  font-size: 17px;
}

.workflow-connector {
  align-self: center;
  height: 1px;
  min-width: 18px;
  flex: 0 0 18px;
  background: #dfe2e8;
}

@media (max-width: 639px) {
  .workflow-nodes {
    margin: 0 -2px;
  }

  .workflow-connector {
    min-width: 12px;
    flex-basis: 12px;
  }
}

.project-card {
  position: relative;
  overflow: hidden;
  transition: transform 180ms ease, border-color 180ms ease, box-shadow 180ms ease;
}

.project-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: linear-gradient(90deg, #e9ebf1, #f2f3f6);
  transition: background 180ms ease;
}

.project-card:hover {
  transform: translateY(-3px);
  border-color: #776cff !important;
  box-shadow: 0 14px 30px rgba(91, 79, 235, 0.12);
}

.project-card:hover::before {
  background: linear-gradient(90deg, #8578ff, #5b4ff0);
}

.project-card-active,
.project-card-active:hover {
  border-color: #776cff !important;
  box-shadow: 0 12px 26px rgba(91, 79, 235, 0.12);
}

.project-card-active::before,
.project-card-active:hover::before {
  background: linear-gradient(90deg, #8578ff, #5b4ff0);
}

@media (prefers-reduced-motion: reduce) {
  .project-card {
    transition: none;
  }

  .project-card:hover {
    transform: none;
  }
}
</style>
