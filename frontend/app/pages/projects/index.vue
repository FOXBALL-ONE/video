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
          <n-select v-model:value="activeProjectId" class="w-full sm:!w-[220px]" :options="projectOptions"
                    size="small" placeholder="选择项目"/>
        </div>
        <div class="rounded-[15px] border border-[#dfe3eb] bg-white p-4 shadow-[0_6px_18px_rgba(35,43,66,0.03)] sm:p-5">
          <div class="workflow-nodes">
            <template v-for="(step, index) in workflowSteps" :key="step.id">
              <span v-if="index" class="workflow-connector" aria-hidden="true"></span>
              <NuxtLink :to="step.route" class="workflow-node group"
                        @click="selectWorkflowProject">
                <span class="workflow-node-icon" :class="step.iconClass" aria-hidden="true">{{ step.icon }}</span>
                <span class="mt-3 text-xs font-semibold text-[#303b51] transition-colors group-hover:text-[#594df0]">{{ step.title }}</span>
                <span class="mt-1 text-[10px] leading-4 text-[#929bad]">{{ step.caption }}</span>
                <span class="mt-3 inline-flex items-center gap-1 text-[10px] font-medium text-[#6654ee]">{{ step.action }} <span aria-hidden="true">↗</span></span>
              </NuxtLink>
            </template>
          </div>
        </div>
      </section>

      <section class="mt-7">
        <div class="mb-4 flex items-center justify-between"><h2 class="m-0 text-lg font-medium">我的项目</h2><span
            class="text-xs text-[#929bad]">{{ projects.length }} 个项目</span></div>
        <div v-if="loading && !projects.length"
             class="rounded-[15px] border border-[#dfe3eb] bg-white p-10 text-center text-xs text-[#8994a8]">正在加载项目...
        </div>
        <div v-else-if="!projects.length"
             class="rounded-[15px] border border-dashed border-[#dfe3eb] bg-white p-10 text-center text-xs text-[#8994a8]">
          暂无项目，点击右上角新建项目。
        </div>
        <div v-else class="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          <n-card v-for="project in projects" :key="project.id"
                  :class="project.id === activeProjectId ? '!border-[#776cff] shadow-[0_8px_20px_rgba(91,79,235,0.08)]' : ''"
                  :content-style="{ padding: '20px' }"
                  class="project-card !rounded-[15px] !border-[#dfe3eb]">
            <div class="flex items-start justify-between gap-3">
              <div class="flex min-w-0 items-center gap-3"><span
                  class="flex h-10 w-10 shrink-0 items-center justify-center rounded-[10px] bg-[#6654ee] text-base font-semibold text-white">{{
                  project.name.slice(0, 1)
                }}</span>
                <div class="min-w-0">
                  <h3 class="m-0 truncate text-sm font-semibold">{{ project.name }}</h3>
                  <p class="mt-1 text-[11px] text-[#929bad]">{{ project.type }}</p>
                </div>
              </div>
              <n-tag :bordered="false" :type="project.status === '草稿' ? 'default' : 'success'" size="small">
                {{ project.status }}
              </n-tag>
            </div>
            <p class="mt-5 min-h-[40px] text-xs leading-5 text-[#687388]">{{ project.description }}</p>
            <div class="mt-5">
              <div class="flex items-center justify-between text-[11px] text-[#8994a8]"><span>制作完成度</span><strong
                  class="text-[#5d52ec]">{{ project.progress }}%</strong></div>
              <n-progress :percentage="project.progress" :show-indicator="false" class="mt-2" color="#6654ee"
                          rail-color="#ececf4" type="line"/>
            </div>
            <div class="mt-5 grid grid-cols-3 gap-2 border-t border-[#edf0f4] pt-4 text-[11px]">
              <div><span class="block text-[#a0a8b8]">版本</span><strong class="mt-1 block font-medium text-[#4b566b]">{{
                  project.version
                }}</strong></div>
              <div><span class="block text-[#a0a8b8]">时长</span><strong class="mt-1 block font-medium text-[#4b566b]">{{
                  project.duration
                }}</strong></div>
              <div><span class="block text-[#a0a8b8]">评分</span><strong class="mt-1 block font-medium text-[#4b566b]">{{
                  project.score
                }}</strong></div>
            </div>
            <div class="mt-5 flex items-center justify-between"><span
                class="text-[10px] text-[#a0a8b8]">更新于 {{ project.updatedAt }}</span>
              <n-button class="!text-[11px] !text-[#5d52ec]" text @click="openProject(project)">
                {{ project.id === activeProjectId ? '进入工作区' : '切换并进入' }} ↗
              </n-button>
            </div>
          </n-card>
        </div>
      </section>
    </div>
  </main>
</template>

<script lang="ts" setup>
import {computed, onMounted, ref} from 'vue'

const route = useRoute()
const message = useMessage()
const {projects, activeProjectId, switchProject, loadProjects, createProject} = useProjectWorkspace()
const showCreate = ref(route.query.create === '1')
const form = ref({name: '', type: '悬疑短片', description: ''})
const loading = ref(false)
const typeOptions = [
  {label: '悬疑短片', value: '悬疑短片'},
  {label: '纪录短片', value: '纪录短片'},
  {label: '科幻概念片', value: '科幻概念片'},
  {label: '品牌宣传片', value: '品牌宣传片'}
]

const projectOptions = computed(() => projects.value.map((project) => ({label: project.name, value: project.id})))
const workflowSteps = computed(() => {
  const projectId = String(activeProjectId.value || '')
  const base = `/projects/${projectId}/workflow`
  return [
    {id: 'script', title: '剧本导入', caption: '故事结构与对白', action: '开始输入', icon: '✦', iconClass: 'bg-[#eeecff] text-[#6654ee]', route: `${base}/script`},
    {id: 'tasks', title: '每集任务卡', caption: '目标与制作约束', action: '配置任务', icon: '▤', iconClass: 'bg-[#fff4e5] text-[#e59a37]', route: `${base}/tasks`},
    {id: 'assets', title: '资产管理', caption: '角色、场景与道具', action: '整理资产', icon: '◇', iconClass: 'bg-[#e9f7f1] text-[#27966c]', route: `${base}/assets`},
    {id: 'shots', title: '分镜规划', caption: '景别、运镜与连续性', action: '规划分镜', icon: '▦', iconClass: 'bg-[#edf4ff] text-[#4285df]', route: `${base}/shots`},
    {id: 'video', title: '画面视频', caption: '画面、配音与合成', action: '制作视频', icon: '▶', iconClass: 'bg-[#f4edff] text-[#9a67d6]', route: `${base}/video`}
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
</style>
