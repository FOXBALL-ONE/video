<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-10 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto grid min-h-[calc(100vh-5rem)] max-w-[1080px] items-center gap-10 lg:grid-cols-[1.05fr_420px]">
      <section class="hidden lg:block">
        <p class="m-0 text-[11px] font-bold tracking-[1px] text-[#5c52ee]">VIDEO WORKSPACE</p>
        <h1 class="mt-6 max-w-[560px] text-[42px] font-medium leading-[1.12]">把每个镜头，推进到成片。</h1>
        <p class="mt-5 max-w-[490px] text-sm leading-7 text-[#7f899d]">
          登录制作工作台，继续管理剧本、资产、分镜和视频工作流。</p>
        <div class="mt-10 grid max-w-[500px] grid-cols-3 gap-3">
          <div v-for="item in highlights" :key="item.title" class="border-l-2 border-[#d8d2ff] pl-3">
            <strong class="block text-sm font-semibold text-[#424d63]">{{ item.title }}</strong>
            <span class="mt-1 block text-[11px] leading-5 text-[#929bad]">{{ item.detail }}</span>
          </div>
        </div>
      </section>

      <section
          class="rounded-[16px] border border-[#dfe3eb] bg-white p-6 shadow-[0_18px_45px_rgba(42,49,76,0.08)] sm:p-8">
        <div class="flex items-center justify-between gap-4">
          <div>
            <p class="m-0 text-[10px] font-bold tracking-[.8px] text-[#6654ee]">SECURE ACCESS</p>
            <h2 class="mt-3 text-2xl font-medium">{{ isRegister ? '创建工作台账号' : '欢迎回来' }}</h2>
          </div>
          <span
              class="flex h-10 w-10 items-center justify-center rounded-[10px] bg-[#6654ee] text-lg font-semibold text-white">V</span>
        </div>

        <n-alert v-if="errorMessage" :show-icon="false" class="mt-5" type="error">{{ errorMessage }}</n-alert>
        <form class="mt-6 space-y-4" @submit.prevent="submit">
          <label class="block text-xs font-medium text-[#566176]">
            用户名
            <input v-model.trim="form.username"
                   autocomplete="username"
                   class="mt-2 h-11 w-full rounded-[9px] border border-[#dfe3eb] px-3 text-sm outline-none transition focus:border-[#776cff] focus:ring-2 focus:ring-[#e8e5ff]" placeholder="输入用户名"/>
          </label>
          <label v-if="isRegister" class="block text-xs font-medium text-[#566176]">
            邮箱
            <input v-model.trim="form.email"
                   autocomplete="email"
                   class="mt-2 h-11 w-full rounded-[9px] border border-[#dfe3eb] px-3 text-sm outline-none transition focus:border-[#776cff] focus:ring-2 focus:ring-[#e8e5ff]" placeholder="name@example.com" type="email"/>
          </label>
          <label class="block text-xs font-medium text-[#566176]">
            密码
            <input v-model="form.password"
                   autocomplete="current-password"
                   class="mt-2 h-11 w-full rounded-[9px] border border-[#dfe3eb] px-3 text-sm outline-none transition focus:border-[#776cff] focus:ring-2 focus:ring-[#e8e5ff]" placeholder="至少 8 位字符" type="password"/>
          </label>
          <button :disabled="loading"
                  class="flex h-11 w-full items-center justify-center rounded-[9px] bg-[#6654ee] text-sm font-semibold text-white transition hover:bg-[#584bdc] disabled:cursor-not-allowed disabled:opacity-60"
                  type="submit">
            {{ loading ? '处理中…' : (isRegister ? '创建账号' : '登录工作台') }}
          </button>
        </form>
        <button class="mt-5 w-full text-center text-xs text-[#6654ee] hover:underline" type="button"
                @click="toggleMode">
          {{ isRegister ? '已有账号？返回登录' : '还没有账号？创建一个' }}
        </button>
      </section>
    </div>
  </main>
</template>

<script lang="ts" setup>
import {useAuth} from '~/composables/useAuth'

const route = useRoute();
const message = useMessage();
const {loading, login, register} = useAuth();
const isRegister = ref(route.query.register === "1");
const errorMessage = ref("");
const form = reactive({username: "", email: "", password: ""});
const highlights = [
  {title: "JWT 会话", detail: "令牌按请求头传输"},
  {title: "Redis 状态", detail: "支持主动退出登录"},
  {title: "角色权限", detail: "按权限进入工作区"},
];

function toggleMode() {
  isRegister.value = !isRegister.value;
  errorMessage.value = "";
}

async function submit() {
  errorMessage.value = "";
  if (!form.username || !form.password || (isRegister.value && !form.email)) {
    errorMessage.value = "请完整填写表单";
    return;
  }
  try {
    if (isRegister.value) {
      await register(form.username, form.email, form.password);
      message.success("账号创建成功，请登录");
      isRegister.value = false;
      form.password = "";
      return;
    }
    await login(form.username, form.password);
    await navigateTo("/");
  } catch (error: any) {
    errorMessage.value = error?.statusMessage || "登录失败，请检查账号和密码";
  }
}
</script>
