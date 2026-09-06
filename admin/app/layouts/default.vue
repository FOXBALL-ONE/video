<template>
  <div class="admin-shell">
    <aside v-if="!isLogin" class="sidebar">
      <div class="brand-mark"><span>V</span><div><strong>Video</strong><small>CONTROL ROOM</small></div></div>
      <nav class="nav-list" aria-label="管理端导航">
        <NuxtLink to="/users" class="nav-link" active-class="nav-link-active"><span class="nav-icon">◎</span>用户管理</NuxtLink>
      </nav>
      <div class="sidebar-footer"><span class="status-dot" />系统在线</div>
    </aside>
    <main :class="['main-area', { 'main-area-full': isLogin }]">
      <header v-if="!isLogin" class="topbar"><div><span class="eyebrow">ADMINISTRATION</span><h1>管理工作台</h1></div><button class="logout-button" type="button" @click="logout">退出登录</button></header>
      <slot />
    </main>
  </div>
</template>

<script setup lang="ts">
const route = useRoute()
const router = useRouter()
const { token } = useAdminApi()
const isLogin = computed(() => route.path === '/login')

async function logout() {
  token.value = null
  await router.push('/login')
}
</script>

<style>
:root { color-scheme: light; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
* { box-sizing: border-box; }
body { margin: 0; background: #f4f7f8; color: #192832; }
button, input { font: inherit; }
.admin-shell { min-height: 100vh; display: flex; }
.sidebar { width: 238px; flex: 0 0 238px; min-height: 100vh; display: flex; flex-direction: column; background: #10252d; color: #d7e3e5; padding: 28px 18px 22px; }
.brand-mark { display: flex; align-items: center; gap: 12px; padding: 0 13px 34px; }
.brand-mark > span { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 9px; color: #10252d; background: #f4b860; font-size: 20px; font-weight: 800; }
.brand-mark strong { display: block; color: #fff; font-size: 16px; letter-spacing: .4px; }
.brand-mark small { display: block; margin-top: 3px; color: #789099; font-size: 9px; letter-spacing: 1.5px; }
.nav-list { display: grid; gap: 7px; }
.nav-link { display: flex; align-items: center; gap: 11px; color: #aabdc1; text-decoration: none; padding: 12px 13px; border-radius: 8px; font-size: 13px; transition: background .2s, color .2s; }
.nav-link:hover, .nav-link-active { color: #fff; background: #1b3b45; }
.nav-icon { width: 20px; color: #f4b860; font-size: 18px; text-align: center; }
.sidebar-footer { display: flex; align-items: center; gap: 8px; margin-top: auto; padding: 13px; color: #789099; font-size: 11px; border-top: 1px solid #23414a; }
.status-dot { width: 7px; height: 7px; background: #52c49c; border-radius: 50%; }
.main-area { min-width: 0; flex: 1; }
.main-area-full { display: flex; align-items: stretch; }
.topbar { height: 94px; display: flex; align-items: center; justify-content: space-between; padding: 0 clamp(22px, 5vw, 66px); background: #fff; border-bottom: 1px solid #e4ebed; }
.eyebrow { color: #84999f; font-size: 10px; font-weight: 700; letter-spacing: 1.6px; }
.topbar h1 { margin: 7px 0 0; font-size: 21px; font-weight: 650; letter-spacing: -.3px; }
.logout-button { border: 1px solid #d7e2e4; color: #60747a; background: #fff; padding: 8px 13px; border-radius: 7px; cursor: pointer; font-size: 12px; }
.logout-button:hover { border-color: #b7c9cc; color: #19323a; }
@media (max-width: 720px) { .sidebar { width: 70px; flex-basis: 70px; padding: 20px 9px; } .brand-mark { padding: 0 9px 28px; } .brand-mark > div, .nav-link:not(.nav-link-active)::after, .nav-link { font-size: 0; } .brand-mark > span { margin: auto; } .nav-link { justify-content: center; padding: 12px 8px; } .nav-icon { font-size: 18px; } .sidebar-footer { justify-content: center; padding: 13px 0; font-size: 0; } .topbar { padding: 0 18px; } }
</style>
