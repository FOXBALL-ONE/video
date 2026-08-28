<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[27px] text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">01 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div>
            <h1 class="m-0 text-[31px] font-medium leading-[1.18]">剧本输入</h1>
            <p class="mt-[11px] text-[13px] text-[#8a94aa]">故事结构、场次、人物与对白，所有修改均纳入版本记录和质量门禁。</p>
          </div>
          <div class="flex w-full items-center gap-2 sm:w-auto">
            <n-button class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿</n-button>
            <n-button class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost aria-label="发布项目" />
          </div>
        </div>
      </header>

      <ProductionSteps active="script" />

      <section class="grid min-h-[838px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card class="editor-card !rounded-[17px] !border-[#dfe3eb]" :content-style="{ padding: '0 25px 24px' }">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div>
              <h2 class="m-0 text-lg font-medium">结构化剧本编辑器</h2>
              <p class="mt-[9px] text-xs text-[#8994a8]">支持正文导入、分场拆解、人物对白提取与版本审批</p>
            </div>
            <n-upload :show-file-list="false" accept=".txt,.doc,.docx" @change="onFileChange">
              <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost>导入剧本</n-button>
            </n-upload>
          </div>

          <n-tabs v-model:value="selectedTab" class="script-tabs" type="line" animated justify-content="space-between">
            <n-tab-pane v-for="tab in editorTabs" :key="tab" :name="tab" :tab="tab" />
          </n-tabs>

          <div class="flex min-h-[71px] flex-col items-start justify-between gap-3 py-3 text-xs text-[#7d879b] sm:flex-row sm:items-center sm:py-0">
            <span>版本 v1.4 · 编辑修订稿</span>
            <n-space :size="6">
              <n-button class="!h-10 !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="aiFormat">✦ AI 结构化</n-button>
              <n-button class="!h-10 !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="lockVersion">锁定版本</n-button>
            </n-space>
          </div>

          <n-input
              v-model:value="scriptText"
              class="script-editor"
              type="textarea"
              :autosize="{ minRows: 10, maxRows: 15 }"
              placeholder="输入或导入剧本文本"
              aria-label="剧本文本"
          />

          <div class="flex min-h-[69px] flex-wrap items-center gap-x-[26px] gap-y-3 border-b border-[#e8eaf0] py-3 sm:py-0">
            <div v-for="stat in stats" :key="stat.label" class="flex items-baseline gap-2">
              <strong class="text-[19px] font-bold text-[#5a6477]">{{ stat.value }}</strong>
              <span class="text-xs text-[#8b96aa]">{{ stat.label }}</span>
            </div>
          </div>

          <section class="pt-[21px]">
            <h3 class="mb-[13px] text-[13px] font-medium">剧本质量门禁</h3>
            <div class="grid grid-cols-1 gap-[9px] sm:grid-cols-2">
              <n-alert
                  v-for="item in qualityItems"
                  :key="item.title"
                  class="quality-alert !min-h-[63px] !rounded-[10px]"
                  :type="item.ok ? 'success' : 'warning'"
                  :show-icon="false"
                  :bordered="false"
              >
                <template #header>{{ item.ok ? '✓' : '!' }} {{ item.title }}</template>
                <span class="text-[11px] text-[#8490a4]">{{ item.note }}</span>
              </n-alert>
            </div>
          </section>
        </n-card>

        <n-card class="assistant-panel !rounded-[17px] !border-[#dfe3eb]" :content-style="{ padding: 0, height: '100%' }">
          <div class="flex min-h-[81px] items-start gap-[13px] border-b border-[#e8eaf0] px-[21px] py-[18px]">
            <span class="text-[22px] leading-[22px] text-[#5d51ef]">✦</span>
            <div>
              <h2 class="m-0 text-[13px] font-semibold">剧本输入助手</h2>
              <p class="mt-[5px] text-[11px] text-[#8e98aa]"><n-badge dot type="success" /> <span class="ml-1">在线 · 已读取当前节点</span></p>
            </div>
          </div>

          <div class="px-[18px] py-[19px]">
            <div class="flex min-h-[90px] flex-col rounded-xl bg-[#f7f7fb] p-[13px]">
              <span class="text-[11px] text-[#929bad]">当前上下文</span>
              <strong class="mt-2 text-[13px] font-semibold text-[#273148]">雨夜来信 · v1.4</strong>
              <div class="mt-[5px] flex flex-wrap gap-1">
                <n-tag v-for="tag in contextTags" :key="tag" size="small" :bordered="false" class="!bg-transparent !px-0 !text-[11px] !text-[#6157e9]">{{ tag }}</n-tag>
              </div>
            </div>
            <div class="mt-[17px] flex gap-[11px] rounded-[11px] bg-[#f5f6f8] py-3 pr-3">
              <span class="w-[21px] shrink-0 text-center text-[#5e52ee]">✦</span>
              <p class="m-0 text-xs leading-[1.65] text-[#687388]">{{ assistantMessage }}</p>
            </div>
          </div>

          <div class="mt-auto border-t border-[#e8eaf0]">
            <div class="min-h-[96px] border-b border-[#e8eaf0] px-[15px] py-[17px]">
              <span class="text-[11px] text-[#8994a8]">推荐操作</span>
              <div class="mt-3 flex flex-wrap gap-2">
                <n-button v-for="suggestion in suggestions" :key="suggestion" class="!h-8 !rounded-lg !border-[#d8d2ff] !px-[10px] !text-[11px] !text-[#5d52ec]" ghost @click="useSuggestion(suggestion)">{{ suggestion }} ↗</n-button>
              </div>
            </div>
            <form class="min-h-[132px] px-[15px] py-[14px]" @submit.prevent="sendMessage">
              <n-input v-model:value="chatInput" type="text" placeholder="告诉助手你想优化什么..." :bordered="false" />
              <div class="flex h-[72px] items-end justify-between text-[11px] text-[#a0a8b8]">
                <span>Enter 发送</span>
                <n-button class="!h-[34px] !w-[34px] !rounded-[9px] !bg-[#b8b1ff] !p-0 !text-[22px] !text-white" quaternary attr-type="submit" aria-label="发送消息">↑</n-button>
              </div>
            </form>
          </div>
        </n-card>
      </section>
    </div>
  </main>
</template>

<script setup>
import { ref } from 'vue'

const message = useMessage()

const editorTabs = ['正文', '场次表', '人物表', '对白表', '版本记录']
const stats = [
  { value: '8', label: '场戏' },
  { value: '3', label: '角色' },
  { value: '42', label: '句对白' },
  { value: '2:36', label: '预计时长' },
  { value: '9.1', label: '结构评分' }
]
const qualityItems = [
  { title: '主冲突清晰', note: '第 1 场建立', ok: true },
  { title: '人物动机完整', note: '3/3 角色', ok: true },
  { title: '第 6 场节奏偏慢', note: '建议压缩 12 秒', ok: false },
  { title: '资产可提取', note: '已识别 12 项', ok: true }
]
const contextTags = ['节点数据', '历史版本', '质量门禁']
const suggestions = ['拆分场次和对白', '检查前三轮钩子', '生成资产需求']

const selectedTab = ref('正文')
const scriptText = ref('雨夜，失意摄影师林夏错过末班车。她在空荡的站台收到一封没有署名的信，寄信人竟是十年前的自己。\n信里只有一句话：不要登上下一班车。远处车灯穿过雨幕，她听见车内有人喊出自己的名字……')
const chatInput = ref('')
const assistantMessage = ref('我是剧本总监助手。可以帮你拆分场次、检查人物动机、优化句子，并整理成可生产的结构化剧本。')

function saveDraft() {
  message.success('草稿已保存')
}

function onFileChange({ file }) {
  if (file?.name) message.success(`已选择 ${file.name}`)
}

function aiFormat() {
  scriptText.value = scriptText.value.trim()
  message.success('AI 结构化完成')
}

function lockVersion() {
  message.success('版本已锁定')
}

function useSuggestion(suggestion) {
  chatInput.value = suggestion
  sendMessage()
}

function sendMessage() {
  const input = chatInput.value.trim()
  if (!input) return
  assistantMessage.value = `收到“${input}”，我会结合当前版本给出可执行的优化建议。`
  chatInput.value = ''
}
</script>

<style scoped>
.editor-card :deep(.n-card__content),
.assistant-panel :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
}

.script-tabs :deep(.n-tabs-nav) {
  height: 49px;
  margin: 0;
  border-bottom: 1px solid #e8eaf0;
}

.script-tabs :deep(.n-tabs-tab) {
  min-width: 82px;
  justify-content: center;
  padding: 0;
  font-size: 12px;
}

.script-tabs :deep(.n-tab-pane) {
  padding: 0;
}

.script-editor :deep(.n-input-wrapper) {
  padding: 0;
}

.script-editor :deep(.n-input__textarea-el) {
  min-height: 310px !important;
  padding: 20px 18px;
  color: #515d73;
  font-size: 14px;
  line-height: 1.8;
}

.quality-alert :deep(.n-alert-body) {
  padding: 11px;
}

.quality-alert :deep(.n-alert-header) {
  font-size: 12px;
  font-weight: 400;
}

@media (max-width: 640px) {
  .editor-card :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
