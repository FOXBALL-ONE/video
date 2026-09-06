<template>
  <div class="login-page">
    <section class="login-panel">
      <div class="login-brand"><span>V</span><div><strong>Video Control Room</strong><small>ADMINISTRATION CONSOLE</small></div></div>
      <div class="login-copy"><span class="eyebrow">SECURE ACCESS</span><h1>欢迎回来</h1><p>登录管理端，继续处理用户与平台运营。</p></div>
      <form class="login-form" @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" placeholder="输入管理员用户名" required /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" placeholder="输入密码" required /></label>
        <p v-if="errorMessage" class="form-error">{{ errorMessage }}</p>
        <button class="primary-button" type="submit" :disabled="loading">{{ loading ? '正在验证…' : '进入管理端' }}<span>→</span></button>
      </form>
    </section>
    <aside class="login-aside"><div class="aside-rule" /><p>OPERATIONS<br><em>with clarity.</em></p><small>视频创作平台 · 管理控制台</small></aside>
  </div>
</template>

<script setup lang="ts">
const username = ref('')
const password = ref('')
const loading = ref(false)
const errorMessage = ref('')
const router = useRouter()
const { token, login } = useAdminApi()

if (token.value) await navigateTo('/users')

async function submit() {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await login(username.value.trim(), password.value)
    token.value = result.access_token
    await router.push('/users')
  } catch (error: unknown) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请检查账号和密码'
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-page { min-height: 100vh; width: 100%; display: grid; grid-template-columns: minmax(0, 1fr) 42%; background: #f4f7f8; }
.login-panel { width: min(430px, calc(100% - 48px)); align-self: center; justify-self: center; }
.login-brand { display: flex; align-items: center; gap: 12px; }
.login-brand > span { display: grid; place-items: center; width: 39px; height: 39px; border-radius: 10px; color: #10252d; background: #f4b860; font-size: 22px; font-weight: 800; }
.login-brand strong { display: block; color: #1b3038; font-size: 15px; }
.login-brand small { display: block; margin-top: 3px; color: #80949a; font-size: 9px; letter-spacing: 1.5px; }
.login-copy { margin: 80px 0 30px; }.eyebrow { color: #b47a2f; font-size: 10px; font-weight: 800; letter-spacing: 1.8px; }.login-copy h1 { margin: 10px 0 8px; color: #19323a; font-size: 34px; font-weight: 650; letter-spacing: -1px; }.login-copy p { margin: 0; color: #7b8d92; font-size: 13px; }
.login-form { display: grid; gap: 18px; }.login-form label { display: grid; gap: 8px; color: #50666c; font-size: 12px; font-weight: 650; }.login-form input { width: 100%; padding: 13px 14px; border: 1px solid #d7e3e5; border-radius: 7px; outline: 0; color: #19323a; background: #fff; font-size: 13px; }.login-form input:focus { border-color: #4f9292; box-shadow: 0 0 0 3px #dceeee; }.form-error { margin: -2px 0 0; color: #bd594e; font-size: 12px; }.primary-button { display: flex; align-items: center; justify-content: space-between; border: 0; border-radius: 7px; padding: 14px 16px; color: #10252d; background: #f4b860; cursor: pointer; font-size: 13px; font-weight: 750; }.primary-button:hover { background: #edaa4c; }.primary-button:disabled { cursor: wait; opacity: .6; }
.login-aside { display: flex; flex-direction: column; justify-content: center; padding: clamp(35px, 9vw, 130px); color: #d8e7e8; background: #173840; }.aside-rule { width: 47px; height: 4px; margin-bottom: 28px; background: #f4b860; }.login-aside p { margin: 0; font-size: clamp(30px, 4vw, 56px); line-height: 1.06; font-weight: 300; letter-spacing: -1px; }.login-aside em { color: #f4b860; font-family: Georgia, serif; font-weight: 400; }.login-aside small { margin-top: 38px; color: #8ea9ad; font-size: 11px; }
@media (max-width: 680px) { .login-page { display: block; }.login-panel { padding: 48px 0 70px; }.login-aside { min-height: 190px; padding: 35px 24px; }.login-aside p { font-size: 30px; }.login-aside small { margin-top: 18px; } }
</style>
