<template>
  <n-card class="assistant-panel min-h-[500px] !rounded-[17px] !border-[#dfe3eb] lg:min-h-[820px]" :content-style="{ padding: 0, height: '100%' }">
    <div class="flex min-h-[81px] items-start gap-[13px] border-b border-[#e8eaf0] px-[21px] py-[18px]">
      <span class="text-[22px] leading-[22px] text-[#5d51ef]">✦</span>
      <div class="min-w-0 flex-1">
        <h2 class="m-0 text-[13px] font-semibold">{{ title }}</h2>
        <p class="mt-[5px] text-[11px] text-[#8e98aa]">
          <n-badge :type="loading ? 'warning' : 'success'" dot/><span class="ml-1">{{ status }}</span>
        </p>
      </div>
      <slot name="header-extra"/>
    </div>

    <div class="px-[18px] py-[19px]">
      <div class="flex min-h-[90px] flex-col rounded-xl bg-[#f7f7fb] p-[13px]">
        <span class="text-[11px] text-[#929bad]">当前上下文</span>
        <strong class="mt-2 truncate text-[13px] font-semibold text-[#273148]" :title="contextTitle">{{ contextTitle }}</strong>
        <div v-if="contextTags.length" class="mt-[5px] flex flex-wrap gap-x-2 gap-y-1">
          <n-tag v-for="tag in contextTags" :key="tag" :bordered="false" class="!bg-transparent !px-0 !text-[11px] !text-[#6157e9]" size="small">
            {{ tag }}
          </n-tag>
        </div>
      </div>

      <div class="assistant-thread mt-[17px] space-y-[11px]">
        <div v-for="(item, index) in thread" :key="index"
             :class="item.error ? 'bg-[#fff7f7]' : item.role === 'user' ? 'bg-[#eef0fb]' : 'bg-[#f5f6f8]'"
             class="flex gap-[11px] rounded-[11px] py-3 pr-3">
          <span :class="item.error ? 'text-[#c98a8a]' : item.role === 'user' ? 'text-[#8b83f0]' : 'text-[#5e52ee]'" class="w-[21px] shrink-0 text-center text-xs">✦</span>
          <div class="min-w-0 flex-1">
            <p :class="item.error ? 'text-[#a34d4d]' : 'text-[#687388]'" class="m-0 whitespace-pre-wrap break-words text-xs leading-[1.65]">{{ item.content }}</p>
            <slot :index="index" :message="item" name="message-extra"/>
          </div>
        </div>
        <div v-if="loading" class="flex gap-[11px] rounded-[11px] bg-[#f5f6f8] py-3 pr-3">
          <span class="w-[21px] shrink-0 text-center text-[#5e52ee]">✦</span>
          <p class="m-0 text-xs leading-[1.65] text-[#9aa3b4]">正在读取当前资产并整理建议…</p>
        </div>
      </div>
    </div>

    <div class="mt-auto border-t border-[#e8eaf0]">
      <div v-if="suggestions.length" class="min-h-[96px] border-b border-[#e8eaf0] px-[15px] py-[17px]">
        <span class="text-[11px] text-[#8994a8]">推荐操作</span>
        <div class="mt-3 flex flex-wrap gap-2">
          <n-button v-for="suggestion in suggestions" :key="suggestion" :disabled="disabled || loading"
                    class="!h-8 !rounded-lg !border-[#d8d2ff] !px-[10px] !text-[11px] !text-[#5d52ec]" ghost
                    @click="send(suggestion)">{{ suggestion }} ↗
          </n-button>
        </div>
      </div>
      <form class="min-h-[132px] px-[15px] py-[14px]" @submit.prevent="send(chatInput)">
        <slot name="composer-extra"/>
        <n-input v-model:value="chatInput" :bordered="false" :disabled="disabled" :placeholder="placeholder"
                 :maxlength="maxlength" aria-label="告诉助手你想优化什么"/>
        <div class="mt-2 flex h-[72px] items-end justify-between text-[11px] text-[#a0a8b8]">
          <span>Enter 发送</span>
          <n-button :disabled="disabled || loading" aria-label="发送消息"
                    class="!h-[34px] !w-[34px] !rounded-[9px] !bg-[#b8b1ff] !p-0 !text-[22px] !text-white"
                    quaternary attr-type="submit">↑
          </n-button>
        </div>
      </form>
    </div>
  </n-card>
</template>

<script lang="ts" setup>
import {computed, ref} from 'vue'

interface PanelMessage {
  role: 'user' | 'assistant'
  content: string
  error?: string
}

const props = defineProps({
  title: {type: String, default: '制作助手'},
  /** 兼容只提供一句开场白的节点；存在 messages 时以对话记录为准。 */
  message: {type: String, default: ''},
  messages: {type: Array as () => PanelMessage[], default: () => []},
  suggestions: {type: Array as () => string[], default: () => []},
  contextTitle: {type: String, default: '未选择剧集'},
  contextTags: {type: Array as () => string[], default: () => []},
  status: {type: String, default: '在线 · 已读取当前节点'},
  placeholder: {type: String, default: '告诉助手你想优化什么...'},
  loading: {type: Boolean, default: false},
  disabled: {type: Boolean, default: false},
  maxlength: {type: Number, default: 2000},
})
const emit = defineEmits(['send'])
const chatInput = ref('')

/** 没有对话记录时退回单条开场白，保持旧节点的展示不变。 */
const thread = computed<PanelMessage[]>(() => {
  if (props.messages.length) return props.messages
  return props.message ? [{role: 'assistant', content: props.message}] : []
})

function send(value: string) {
  const text = value.trim()
  if (!text || props.disabled || props.loading) return
  emit('send', text)
  chatInput.value = ''
}
</script>

<style scoped>
.assistant-panel :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
}

.assistant-thread {
  max-height: 380px;
  overflow-y: auto;
  overscroll-behavior: contain;
}
</style>
