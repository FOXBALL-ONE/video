<template>
  <nav class="my-[23px] mt-[31px] grid grid-cols-2 gap-[13px] md:grid-cols-4" aria-label="制作流程">
    <NuxtLink
      v-for="step in steps"
      :key="step.id"
      :to="routes[step.id]"
      class="block rounded-[14px] focus:outline-none focus:ring-2 focus:ring-[#8f87ff] focus:ring-offset-2"
      :aria-current="step.id === active ? 'page' : undefined"
    >
      <n-card
        class="production-step min-h-[119px] cursor-pointer !rounded-[14px] transition-all"
        :class="step.id === active ? 'production-step-active shadow-[0_10px_24px_rgba(91,79,235,0.1)]' : ''"
        size="small"
        hoverable
      >
        <div class="flex h-full flex-col items-start px-1 py-1">
          <span class="h-7 text-[23px] leading-6" :class="step.id === active ? 'text-[#594df0]' : 'text-[#9099a9]'">{{ step.icon }}</span>
          <strong class="mt-[7px] text-sm font-semibold" :class="step.id === active ? 'text-[#5a4ef0]' : 'text-[#8992a6]'">{{ step.title }}</strong>
          <span class="mt-[7px] text-xs text-[#929bad]">{{ step.caption }}</span>
        </div>
      </n-card>
    </NuxtLink>
  </nav>
</template>

<script setup>
defineProps({ active: { type: String, required: true } })

const steps = [
  { id: 'script', title: '剧本输入', caption: '故事结构、场次、人物与对白', icon: '✦' },
  { id: 'assets', title: '资产清单', caption: '角色、场景、道具与声音', icon: '◇' },
  { id: 'shots', title: '分镜管理', caption: '景别、运镜、动作与连续性', icon: '▦' },
  { id: 'video', title: '视频制作', caption: '画面、配音、音效与合成', icon: '▶' }
]
const routes = { script: '/script', assets: '/assets', shots: '/shots', video: '/video' }
</script>

<style scoped>
.production-step :deep(.n-card__content) {
  height: 100%;
  padding: 16px;
}

.production-step-active {
  border-color: #6d61ff !important;
}
</style>
