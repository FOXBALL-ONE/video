<template>
  <n-card class="assistant-panel min-h-[500px] !rounded-[17px] !border-[#dfe3eb] lg:min-h-[820px]" :content-style="{ padding: 0, height: '100%' }">
    <div class="flex min-h-[81px] items-start gap-[13px] border-b border-[#e8eaf0] px-[21px] py-[18px]">
      <span class="text-[22px] leading-[22px] text-[#5d51ef]">✦</span>
      <div>
        <h2 class="m-0 text-[13px] font-semibold">{{ title }}</h2>
        <p class="mt-[5px] text-[11px] text-[#8e98aa]"><n-badge dot type="success" /><span class="ml-1">在线 · 已读取当前节点</span></p>
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
        <p class="m-0 text-xs leading-[1.65] text-[#687388]">{{ message }}</p>
      </div>
    </div>

    <div class="mt-auto border-t border-[#e8eaf0]">
      <div class="min-h-[96px] border-b border-[#e8eaf0] px-[15px] py-[17px]">
        <span class="text-[11px] text-[#8994a8]">推荐操作</span>
        <div class="mt-3 flex flex-wrap gap-2">
          <n-button v-for="suggestion in suggestions" :key="suggestion" class="!h-8 !rounded-lg !border-[#d8d2ff] !px-[10px] !text-[11px] !text-[#5d52ec]" ghost @click="send(suggestion)">{{ suggestion }} ↗</n-button>
        </div>
      </div>
      <form class="min-h-[132px] px-[15px] py-[14px]" @submit.prevent="send(chatInput)">
        <n-input v-model:value="chatInput" placeholder="告诉助手你想优化什么..." :bordered="false" aria-label="告诉助手你想优化什么" />
        <div class="flex h-[72px] items-end justify-between text-[11px] text-[#a0a8b8]">
          <span>Enter 发送</span>
          <n-button class="!h-[34px] !w-[34px] !rounded-[9px] !bg-[#b8b1ff] !p-0 !text-[22px] !text-white" quaternary attr-type="submit" aria-label="发送消息">↑</n-button>
        </div>
      </form>
    </div>
  </n-card>
</template>

<script setup>
import { ref } from 'vue'

defineProps({ title: { type: String, default: '制作助手' }, message: { type: String, default: '' }, suggestions: { type: Array, default: () => [] } })
const emit = defineEmits(['send'])
const chatInput = ref('')
const contextTags = ['节点数据', '历史版本', '质量门禁']

function send(value) {
  const message = value.trim()
  if (!message) return
  emit('send', message)
  chatInput.value = ''
}
</script>

<style scoped>
.assistant-panel :deep(.n-card__content) {
  display: flex;
  flex-direction: column;
}
</style>
