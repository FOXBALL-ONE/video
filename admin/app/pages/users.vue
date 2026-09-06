<template>
  <div class="users-page">
    <section class="page-heading"><div><span class="eyebrow">DIRECTORY / ACCESS</span><h2>用户管理</h2><p>管理平台账号、角色和访问状态。</p></div><button class="primary-button compact" type="button" @click="openCreate">＋ 新建用户</button></section>

    <section class="stat-grid" aria-label="用户统计">
      <div class="stat-card"><span>用户总数</span><strong>{{ total }}</strong><small>当前筛选结果</small></div>
      <div class="stat-card"><span>已启用</span><strong>{{ enabledCount }}</strong><small>可正常登录</small></div>
      <div class="stat-card"><span>已停用</span><strong>{{ Math.max(total - enabledCount, 0) }}</strong><small>需要复核的账号</small></div>
    </section>

    <section class="table-panel">
      <div class="filter-bar">
        <label class="search-box"><span>⌕</span><input v-model="keyword" placeholder="搜索用户名或邮箱" @keyup.enter="search" /></label>
        <select v-model="enabledFilter" aria-label="账号状态"><option value="">全部状态</option><option value="true">已启用</option><option value="false">已停用</option></select>
        <select v-model="roleFilter" aria-label="角色"><option value="">全部角色</option><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select>
        <button class="filter-button" type="button" @click="search">筛选</button>
      </div>

      <div v-if="errorMessage" class="error-banner">{{ errorMessage }}</div>
      <div v-if="loading" class="empty-state">正在加载用户…</div>
      <div v-else-if="!users.length" class="empty-state"><strong>没有匹配的用户</strong><span>调整筛选条件，或创建一个新账号。</span></div>
      <div v-else class="table-scroll"><table><thead><tr><th>用户</th><th>角色</th><th>状态</th><th>账号 ID</th><th class="align-right">操作</th></tr></thead><tbody>
        <tr v-for="user in users" :key="user.user_id">
          <td><div class="identity"><span class="avatar">{{ user.username.slice(0, 1).toUpperCase() }}</span><div><strong>{{ user.username }}</strong><small>{{ user.email }}</small></div></div></td>
          <td><div class="role-list"><span v-for="role in user.roles" :key="role" :class="['role-tag', role === 'ADMIN' ? 'role-admin' : '']">{{ role === 'ADMIN' ? '管理员' : '普通用户' }}</span><span v-if="!user.roles.length" class="muted">未分配</span></div></td>
          <td><button :class="['status-pill', user.enabled ? 'status-enabled' : 'status-disabled']" type="button" @click="toggleStatus(user)"><span />{{ user.enabled ? '已启用' : '已停用' }}</button></td>
          <td><code>#{{ user.user_id }}</code></td>
          <td class="align-right"><button class="text-button" type="button" @click="openEdit(user)">编辑</button></td>
        </tr>
      </tbody></table></div>

      <footer class="pagination"><span>共 {{ total }} 个账号</span><div><button type="button" :disabled="page <= 1" @click="changePage(page - 1)">‹</button><b>{{ page }}</b><span>/ {{ Math.max(totalPages, 1) }}</span><button type="button" :disabled="page >= totalPages" @click="changePage(page + 1)">›</button></div></footer>
    </section>

    <div v-if="modalOpen" class="modal-backdrop" @click.self="closeModal"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title"><header><div><span class="eyebrow">ACCOUNT SETTINGS</span><h3 id="modal-title">{{ editingId ? '编辑用户' : '新建用户' }}</h3></div><button class="close-button" type="button" aria-label="关闭" @click="closeModal">×</button></header><form @submit.prevent="saveUser"><label>用户名<input v-model="form.username" maxlength="50" required placeholder="例如：lin.ming" /></label><label>邮箱<input v-model="form.email" type="email" maxlength="255" required placeholder="name@example.com" /></label><label>{{ editingId ? '重置密码（可选）' : '初始密码' }}<input v-model="form.password" type="password" :required="!editingId" minlength="6" maxlength="100" :placeholder="editingId ? '留空则保持原密码' : '至少 6 位字符'" /></label><label>角色<select v-model="form.roleCode"><option value="USER">普通用户</option><option value="ADMIN">管理员</option></select></label><label class="toggle-row"><span><strong>允许登录</strong><small>停用后账号无法登录客户端</small></span><input v-model="form.enabled" type="checkbox" /></label><p v-if="formError" class="form-error">{{ formError }}</p><footer><button class="cancel-button" type="button" @click="closeModal">取消</button><button class="primary-button compact" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存用户' }}</button></footer></form></section></div>
  </div>
</template>

<script setup lang="ts">
import type { UserRecord } from '~/types/users'
import type { UserPayload } from '~/composables/useAdminApi'

const api = useAdminApi()
const router = useRouter()
const keyword = ref('')
const enabledFilter = ref('')
const roleFilter = ref('')
const users = ref<UserRecord[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(1)
const size = 12
const total = ref(0)
const totalPages = ref(0)
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const formError = ref('')
const form = reactive({ username: '', email: '', password: '', roleCode: 'USER', enabled: true })
const enabledCount = computed(() => users.value.filter((user) => user.enabled).length)

if (!api.token.value) await navigateTo('/login')

async function loadUsers() {
  loading.value = true
  errorMessage.value = ''
  try {
    const data = await api.listUsers({ keyword: keyword.value.trim() || undefined, enabled: enabledFilter.value === '' ? undefined : enabledFilter.value === 'true', role_code: roleFilter.value || undefined, page: page.value, size })
    users.value = data.items
    total.value = data.total
    totalPages.value = data.total_pages
  } catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '用户加载失败' }
  finally { loading.value = false }
}

function search() { page.value = 1; void loadUsers() }
function changePage(nextPage: number) { page.value = nextPage; void loadUsers() }

function resetForm() { form.username = ''; form.email = ''; form.password = ''; form.roleCode = 'USER'; form.enabled = true; formError.value = '' }
function openCreate() { editingId.value = null; resetForm(); modalOpen.value = true }
function openEdit(user: UserRecord) { editingId.value = user.user_id; form.username = user.username; form.email = user.email; form.password = ''; form.roleCode = user.roles.includes('ADMIN') ? 'ADMIN' : 'USER'; form.enabled = user.enabled; formError.value = ''; modalOpen.value = true }
function closeModal() { if (!saving.value) modalOpen.value = false }

async function saveUser() {
  saving.value = true
  formError.value = ''
  const payload: UserPayload = { username: form.username.trim(), email: form.email.trim(), password: form.password || undefined, role_codes: [form.roleCode], enabled: form.enabled }
  try {
    if (editingId.value) await api.updateUser(editingId.value, payload)
    else await api.createUser(payload)
    modalOpen.value = false
    await loadUsers()
  } catch (error: unknown) { formError.value = error instanceof Error ? error.message : '保存失败' }
  finally { saving.value = false }
}

async function toggleStatus(user: UserRecord) {
  try { await api.setUserStatus(user.user_id, !user.enabled); user.enabled = !user.enabled }
  catch (error: unknown) { errorMessage.value = error instanceof Error ? error.message : '状态更新失败' }
}

onMounted(() => { void loadUsers() })
watch(() => api.token.value, (value) => { if (!value) void router.push('/login') })
</script>

<style scoped>
.users-page { max-width: 1220px; margin: 0 auto; padding: 44px clamp(22px, 5vw, 66px) 70px; }.page-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }.eyebrow { color: #b47a2f; font-size: 10px; font-weight: 800; letter-spacing: 1.7px; }.page-heading h2 { margin: 9px 0 6px; color: #19323a; font-size: 29px; letter-spacing: -.8px; }.page-heading p { margin: 0; color: #7c8f94; font-size: 13px; }.primary-button { border: 0; border-radius: 7px; padding: 12px 16px; color: #10252d; background: #f4b860; cursor: pointer; font-size: 12px; font-weight: 750; }.primary-button:hover { background: #edaa4c; }.primary-button:disabled { cursor: wait; opacity: .6; }.primary-button.compact { white-space: nowrap; padding: 10px 14px; }
.stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-top: 32px; }.stat-card { padding: 19px 20px; background: #fff; border: 1px solid #e1eaec; border-radius: 8px; }.stat-card span, .stat-card small { display: block; color: #809398; font-size: 11px; }.stat-card strong { display: block; margin: 10px 0 5px; color: #19323a; font-size: 28px; font-weight: 650; }.table-panel { margin-top: 25px; overflow: hidden; background: #fff; border: 1px solid #e1eaec; border-radius: 8px; }.filter-bar { display: flex; align-items: center; gap: 10px; padding: 16px; border-bottom: 1px solid #e9eff0; }.search-box { display: flex; align-items: center; flex: 1; min-width: 180px; gap: 7px; padding: 0 11px; border: 1px solid #d8e3e5; border-radius: 6px; color: #91a2a6; }.search-box input { width: 100%; border: 0; outline: 0; padding: 10px 0; color: #1e333a; font-size: 12px; }.filter-bar select { border: 1px solid #d8e3e5; border-radius: 6px; padding: 10px 28px 10px 10px; color: #536a70; background: #fff; font-size: 12px; }.filter-button { border: 1px solid #b8ced0; border-radius: 6px; padding: 10px 15px; color: #35696d; background: #f5fbfb; cursor: pointer; font-size: 12px; font-weight: 650; }.table-scroll { overflow-x: auto; }table { width: 100%; border-collapse: collapse; min-width: 670px; }th { padding: 13px 18px; color: #8a9ba0; background: #fbfcfc; font-size: 10px; font-weight: 750; letter-spacing: .6px; text-align: left; }td { padding: 16px 18px; border-top: 1px solid #eef2f2; color: #435a60; font-size: 12px; }tr:hover td { background: #fcfefe; }.align-right { text-align: right; }.identity { display: flex; align-items: center; gap: 10px; }.avatar { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 8px; color: #2f6d70; background: #dff0ee; font-size: 12px; font-weight: 800; }.identity strong, .identity small { display: block; }.identity strong { color: #253d44; font-size: 12px; }.identity small { margin-top: 4px; color: #8b9ca0; font-size: 11px; }.role-list { display: flex; flex-wrap: wrap; gap: 5px; }.role-tag { padding: 4px 7px; border-radius: 4px; color: #397477; background: #e8f4f2; font-size: 10px; }.role-admin { color: #95672b; background: #fff1d9; }.muted { color: #9aabad; }.status-pill { display: inline-flex; align-items: center; gap: 6px; border: 0; padding: 5px 8px; border-radius: 4px; cursor: pointer; font-size: 10px; }.status-pill span { width: 6px; height: 6px; border-radius: 50%; }.status-enabled { color: #28735f; background: #e5f5ef; }.status-enabled span { background: #3ab486; }.status-disabled { color: #7d8587; background: #edf0f0; }.status-disabled span { background: #9ca7a8; }code { color: #85969a; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }.text-button { border: 0; padding: 5px; color: #3d7c7e; background: transparent; cursor: pointer; font-size: 12px; font-weight: 650; }.text-button:hover { color: #b47a2f; }.pagination { display: flex; justify-content: space-between; align-items: center; padding: 14px 18px; color: #899a9e; font-size: 11px; }.pagination div { display: flex; align-items: center; gap: 9px; }.pagination button { width: 27px; height: 27px; border: 1px solid #d9e4e5; border-radius: 5px; color: #547177; background: #fff; cursor: pointer; }.pagination button:disabled { cursor: not-allowed; opacity: .35; }.pagination b { color: #27484d; font-size: 11px; }
.empty-state { display: grid; justify-items: center; gap: 8px; padding: 72px 20px; color: #829397; font-size: 12px; }.empty-state strong { color: #526a70; font-size: 14px; }.error-banner { margin: 14px 16px 0; padding: 10px 12px; border-radius: 5px; color: #a15148; background: #fff2ef; font-size: 12px; }.modal-backdrop { position: fixed; z-index: 10; inset: 0; display: grid; place-items: center; padding: 20px; background: rgba(14, 34, 40, .42); }.modal { width: min(460px, 100%); padding: 25px; border-radius: 9px; background: #fff; box-shadow: 0 24px 60px rgba(15, 40, 45, .2); }.modal header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 28px; }.modal h3 { margin: 8px 0 0; color: #19323a; font-size: 22px; }.close-button { border: 0; color: #7c9195; background: transparent; cursor: pointer; font-size: 24px; line-height: 1; }.modal form { display: grid; gap: 16px; }.modal label:not(.toggle-row) { display: grid; gap: 7px; color: #52676c; font-size: 11px; font-weight: 700; }.modal input, .modal select { width: 100%; border: 1px solid #d7e3e5; border-radius: 6px; padding: 11px 12px; outline: 0; color: #243d43; background: #fff; font-size: 12px; }.modal input:focus, .modal select:focus { border-color: #4d9291; box-shadow: 0 0 0 3px #e1f1ef; }.toggle-row { display: flex; justify-content: space-between; align-items: center; padding: 11px 12px; border: 1px solid #e2eaeb; border-radius: 6px; }.toggle-row strong, .toggle-row small { display: block; }.toggle-row strong { color: #425d63; font-size: 12px; }.toggle-row small { margin-top: 4px; color: #8ba0a4; font-size: 10px; }.toggle-row input { width: 17px; height: 17px; accent-color: #3f8582; }.form-error { margin: -3px 0 0; color: #b3574e; font-size: 11px; }.modal footer { display: flex; justify-content: flex-end; gap: 9px; margin-top: 8px; }.cancel-button { border: 1px solid #d7e3e5; border-radius: 7px; padding: 10px 15px; color: #60767b; background: #fff; cursor: pointer; font-size: 12px; }
@media (max-width: 680px) { .users-page { padding: 30px 16px 50px; }.page-heading { align-items: flex-start; flex-direction: column; }.stat-grid { gap: 8px; }.stat-card { padding: 14px; }.stat-card strong { font-size: 22px; }.filter-bar { flex-wrap: wrap; }.search-box { flex-basis: 100%; }.filter-bar select, .filter-button { flex: 1; }.pagination { padding: 12px; } }
</style>
