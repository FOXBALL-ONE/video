<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-7 border-b border-[#e5e8ef] pb-8 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">VIDEO WORKSPACE · PROJECT
            {{ activeProject.code }}</p>
          <h1 class="mt-5 text-[31px] font-medium leading-[1.18]">{{ activeProject.name }}</h1>
          <p class="mt-3 max-w-[620px] text-[13px] leading-6 text-[#8a94aa]">{{ activeProject.description }}
            从故事到成片，在一个工作台里推进每个制作节点。</p>
        </div>
        <div class="flex w-full gap-2 sm:w-auto">
          <n-button class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿</n-button>
          <n-button class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none"
                    color="#6654ee" type="primary" @click="openNextStep">
            继续制作
          </n-button>
        </div>
      </header>

      <section aria-label="项目概览" class="mt-7 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <n-card v-for="stat in overviewStats" :key="stat.label" :content-style="{ padding: '17px 18px' }"
                class="!rounded-[13px] !border-[#dfe3eb]">
          <span class="text-[11px] text-[#8994a8]">{{ stat.label }}</span>
          <div class="mt-2 flex items-baseline gap-2">
            <strong class="text-[23px] font-semibold text-[#303b51]">{{ stat.value }}</strong>
            <span class="text-[11px] text-[#929bad]">{{ stat.detail }}</span>
          </div>
        </n-card>
      </section>

      <section class="mt-7 grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        <n-card :content-style="{ padding: '0 24px 24px' }" class="!rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[108px] flex-col items-start justify-between gap-4 border-b border-[#e8eaf0] px-1 py-5 sm:flex-row sm:items-center sm:px-2">
            <div>
              <h2 class="m-0 text-lg font-medium">制作流程</h2>
              <p class="mt-2 text-xs text-[#8994a8]">按顺序完成四个节点，也可以直接进入任意环节处理任务。</p>
            </div>
            <n-tag :bordered="false" :type="activeProject.status === '草稿' ? 'default' : 'success'"
                   class="!rounded-md">{{ activeProject.status }} ·
              {{ activeProject.version }}
            </n-tag>
          </div>

          <div class="grid grid-cols-1 gap-3 pt-5 sm:grid-cols-2">
            <NuxtLink
                v-for="(step, index) in steps"
                :key="step.id"
                :to="step.route"
                class="route-card group flex min-h-[145px] flex-col rounded-[13px] border border-[#dfe3eb] p-4 transition-all hover:-translate-y-0.5 hover:border-[#776cff] hover:shadow-[0_10px_24px_rgba(91,79,235,0.1)] focus:outline-none focus:ring-2 focus:ring-[#8f87ff] focus:ring-offset-2"
            >
              <div class="flex items-start justify-between">
                <span
                    class="flex h-9 w-9 items-center justify-center rounded-[10px] bg-[#f0eeff] text-[19px] text-[#5b4ff0]">{{
                    step.icon
                  }}</span>
                <span class="text-[11px] font-semibold text-[#a0a8b8]">0{{ index + 1 }} / 04</span>
              </div>
              <strong class="mt-4 text-sm font-semibold text-[#303b51] group-hover:text-[#594df0]">{{
                  step.title
                }}</strong>
              <span class="mt-1.5 text-xs text-[#8994a8]">{{ step.caption }}</span>
              <div class="mt-auto flex items-center justify-between pt-4">
                <n-tag :bordered="false" :type="step.tone" size="small">{{ step.status }}</n-tag>
                <span aria-hidden="true" class="text-xs text-[#6654ee]">进入节点 ↗</span>
              </div>
            </NuxtLink>
          </div>
        </n-card>

        <n-card :content-style="{ padding: 0 }" class="!rounded-[17px] !border-[#dfe3eb]">
          <div class="border-b border-[#e8eaf0] px-5 py-[18px]">
            <h2 class="m-0 text-[13px] font-semibold">项目状态</h2>
            <p class="mt-1.5 text-[11px] text-[#8e98aa]">最近一次同步 · 今天 14:32</p>
          </div>
          <div class="space-y-5 px-5 py-5">
            <div>
              <div class="flex items-center justify-between text-xs"><span
                  class="text-[#687388]">整体完成度</span><strong class="text-[#5a4ef0]">{{
                  activeProject.progress
                }}%</strong></div>
              <n-progress :percentage="activeProject.progress" :show-indicator="false" class="mt-3" color="#6654ee"
                          rail-color="#ececf4" type="line"/>
            </div>
            <div class="rounded-[11px] bg-[#fff9ed] px-3 py-3 text-[11px] leading-5 text-[#9a7b48]">
              <strong class="text-[#ae7620]">{{ activeProject.pending }}</strong>
              <p class="m-0 mt-1">{{ activeProject.pendingDetail }}</p>
            </div>
            <div>
              <span class="text-[11px] text-[#8994a8]">版本信息</span>
              <dl class="mt-3 space-y-2 text-xs">
                <div class="flex justify-between gap-3">
                  <dt class="text-[#929bad]">当前版本</dt>
                  <dd class="m-0 font-medium text-[#4b566b]">{{ activeProject.version }} 编辑稿</dd>
                </div>
                <div class="flex justify-between gap-3">
                  <dt class="text-[#929bad]">最后编辑</dt>
                  <dd class="m-0 font-medium text-[#4b566b]">{{ activeProject.editor }}</dd>
                </div>
                <div class="flex justify-between gap-3">
                  <dt class="text-[#929bad]">质量评分</dt>
                  <dd class="m-0 font-medium text-[#4b566b]">{{ activeProject.score }} / 10</dd>
                </div>
              </dl>
            </div>
          </div>
          <div class="border-t border-[#e8eaf0] px-5 py-4">
            <n-button block class="!h-9 !rounded-[9px] !border-[#d8d2ff] !text-[#5d52ec]" ghost @click="openScript">
              查看项目详情
            </n-button>
          </div>
        </n-card>
      </section>

      <section class="mt-5 grid grid-cols-1 gap-5 md:grid-cols-2">
        <n-card :content-style="{ padding: '18px 20px' }" class="!rounded-[15px] !border-[#dfe3eb]">
          <div class="flex items-center justify-between"><h2 class="m-0 text-[13px] font-semibold">最近活动</h2><span
              class="text-[11px] text-[#929bad]">今日</span></div>
          <div class="mt-4 space-y-3">
            <div v-for="activity in activeProject.activities" :key="activity.title"
                 class="flex items-start gap-3 text-xs">
              <span :class="activity.dot" class="mt-1 h-2 w-2 shrink-0 rounded-full"></span>
              <div class="min-w-0 flex-1"><p class="m-0 text-[#687388]">{{ activity.title }}</p><span
                  class="mt-1 block text-[11px] text-[#a0a8b8]">{{ activity.time }}</span></div>
            </div>
          </div>
        </n-card>
        <n-card :content-style="{ padding: '18px 20px' }" class="!rounded-[15px] !border-[#dfe3eb]">
          <div class="flex items-center justify-between"><h2 class="m-0 text-[13px] font-semibold">快捷操作</h2><span
              class="text-[11px] text-[#929bad]">推荐</span></div>
          <div class="mt-4 grid grid-cols-1 gap-2 sm:grid-cols-3">
            <n-button v-for="action in quickActions" :key="action.label"
                      class="!h-auto !min-h-[54px] !justify-start !rounded-[10px] !border-[#e0ddff] !px-3 !py-2 !text-left !text-[11px] !text-[#5d52ec]"
                      ghost @click="runAction(action)">
              <span class="flex flex-col items-start gap-1"><strong class="font-medium">{{
                  action.label
                }}</strong><small class="text-[10px] text-[#929bad]">{{ action.detail }}</small></span>
            </n-button>
          </div>
        </n-card>
      </section>
    </div>
  </main>
</template>

<script setup>
import {computed} from 'vue'

const message = useMessage()
const {activeProject} = useProjectWorkspace()

const steps = [
  {
    id: 'script',
    route: '/graph/script',
    title: '剧本输入',
    caption: '故事结构、场次、人物与对白',
    status: '已完成',
    tone: 'success',
    icon: '✦'
  },
  {
    id: 'assets',
    route: '/graph/assets',
    title: '资产清单',
    caption: '角色、场景、道具与声音',
    status: '进行中',
    tone: 'warning',
    icon: '◇'
  },
  {
    id: 'shots',
    route: '/graph/shots',
    title: '分镜管理',
    caption: '景别、运镜、动作与连续性',
    status: '待优化',
    tone: 'warning',
    icon: '▦'
  },
  {
    id: 'video',
    route: '/graph/video',
    title: '视频制作',
    caption: '画面、配音、音效与合成',
    status: '待开始',
    tone: 'default',
    icon: '▶'
  }
]
const overviewStats = computed(() => [
  {label: '制作节点', value: '4', detail: '个'},
  {label: '当前版本', value: activeProject.value.version, detail: '编辑稿'},
  {label: '预计时长', value: activeProject.value.duration, detail: '成片'},
  {label: '质量评分', value: activeProject.value.score, detail: '/ 10'}
])
const quickActions = [
  {label: '导入新剧本', detail: '开始新的版本', route: '/graph/script'},
  {label: '补齐资产', detail: '处理 3 项缺口', route: '/graph/assets'},
  {label: '检查分镜', detail: '优化镜头连续性', route: '/graph/shots'}
]

function saveDraft() {
  message.success('草稿已保存')
}

function openNextStep() {
  navigateTo('/graph/assets')
}

function openScript() {
  navigateTo('/graph/script')
}

function runAction(action) {
  navigateTo(action.route)
}
</script>
