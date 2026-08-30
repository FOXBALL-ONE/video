<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-[18px] text-[#20283b] sm:px-[27px]">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">03 / 04 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-6 sm:flex-row sm:items-end">
          <div><h1 class="m-0 text-[31px] font-medium">分镜管理</h1>
            <p class="mt-[11px] text-[13px] text-[#8a94aa]">景别、运镜、动作与连续性，所有修改均纳入版本记录和质量门禁。</p>
          </div>
          <div class="flex w-full gap-2 sm:w-auto">
            <n-button :loading="saving" class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿
            </n-button>
            <n-button aria-label="发布项目"
                      class="!h-[42px] !min-w-0 flex-1 !rounded-[10px] !border-[#665bff] sm:!w-[145px] sm:flex-none" ghost/>
          </div>
        </div>
      </header>

      <ProductionSteps active="shots"/>

      <section class="grid min-h-[820px] grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="shots-panel !rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">分镜设计与镜头调度</h2>
              <p class="mt-[9px] text-xs text-[#8994a8]">逐镜管理画面、动作、运镜、台词、时长和生成提示词</p></div>
            <n-button class="!h-[41px] !rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="addShot">＋
              新增镜头
            </n-button>
          </div>

          <div class="grid min-h-[507px] grid-cols-1 pt-5 sm:grid-cols-[255px_1fr]">
            <aside
                class="flex overflow-x-auto border-b border-[#e8eaf0] pb-3 sm:block sm:border-b-0 sm:border-r sm:pb-0 sm:pr-5">
              <button
                  v-for="shot in shots"
                  :key="shot.id"
                  :class="currentShot === shot.id ? 'bg-[#efecff] text-[#5a4ef0]' : 'bg-transparent'"
                  class="grid min-h-[62px] min-w-[210px] grid-cols-[24px_1fr_auto] rounded-[10px] px-2 py-3 text-left text-[#7d899d] sm:min-w-0 sm:w-full"
                  type="button"
                  @click="selectShot(shot)"
              >
                <strong class="text-xs font-medium">{{ String(shot.sequence_no).padStart(2, '0') }}</strong>
                <span class="flex min-w-0 flex-col"><b class="text-xs font-medium text-[#4b566b]">{{
                    shot.title
                  }}</b><small class="mt-[7px] truncate text-[10px]">{{ shotMeta(shot) }}</small></span>
                <n-tag :bordered="false" :type="shotTone(shot.status) === 'done' ? 'success' : 'warning'" size="tiny">
                  {{ statusLabel[shot.status] || shot.status }}
                </n-tag>
              </button>
            </aside>

            <div class="py-[15px] sm:py-0 sm:pl-5">
              <div
                  class="flex h-[230px] items-end justify-between rounded-[13px] bg-[linear-gradient(130deg,#333b50,#a16d7f)] p-[14px] text-xs text-white">
                <span>镜头 {{
                    currentShot == null ? '--' : String(shots.find((shot) => shot.id === currentShot)?.sequence_no || '').padStart(2, '0')
                  }}</span>
                <n-button class="!text-[#5c50ed]" secondary size="small" @click="message.success('动态预览已启动')">▶
                  预览动态
                </n-button>
              </div>
              <n-form class="grid grid-cols-1 gap-x-[11px] pt-3 sm:grid-cols-2" label-placement="top">
                <n-form-item label="镜头标题">
                  <n-input v-model:value="form.title"/>
                </n-form-item>
                <n-form-item label="场景 / 时间">
                  <n-input v-model:value="form.scene"/>
                </n-form-item>
                <n-form-item label="景别与运镜">
                  <n-input v-model:value="form.camera" :rows="2" type="textarea"/>
                </n-form-item>
                <n-form-item label="人物动作">
                  <n-input v-model:value="form.action" :rows="2" type="textarea"/>
                </n-form-item>
                <n-form-item label="对白 / 旁白">
                  <n-input v-model:value="form.dialogue" :rows="2" type="textarea"/>
                </n-form-item>
                <n-form-item label="生成提示词">
                  <n-input v-model:value="form.prompt" :rows="2" type="textarea"/>
                </n-form-item>
              </n-form>
            </div>
          </div>

          <div
              class="flex min-h-[57px] flex-wrap items-start gap-[10px] border-t border-[#e8eaf0] py-[15px] text-[10px] sm:items-center sm:py-0">
            <strong class="mr-[3px] text-[11px]">连续性检查</strong>
            <template v-if="continuityIssues.length">
              <n-tag v-for="issue in continuityIssues.slice(0, 3)" :key="issue.id" :bordered="false" size="small"
                     type="warning">! {{ issue.description }}
              </n-tag>
            </template>
            <n-tag v-else :bordered="false" size="small" type="success">✓ 连续性检查通过</n-tag>
            <n-button :disabled="!continuityIssues.length" :loading="resolving" class="sm:!ml-auto !text-[#ad7423]" secondary
                      size="small" @click="resolveIssues">一键修复
            </n-button>
          </div>
        </n-card>

        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="分镜管理助手"
                        @send="updateAssistant"/>
      </section>
    </div>
  </main>
</template>

<script lang="ts" setup>
import {onMounted, ref, watch} from 'vue'

const message = useMessage()
const http = useHttp()
const {activeProject, loadProjects} = useProjectWorkspace()

interface ShotData {
  id: number;
  sequence_no: number;
  title: string;
  location?: string | null;
  time_of_day?: string | null;
  interior_exterior?: string | null;
  shot_size?: string | null;
  camera_movement?: string | null;
  action?: string | null;
  dialogue?: string | null;
  prompt?: string | null;
  duration_seconds: number;
  status: string
}

interface ContinuityIssue {
  id: number;
  from_shot_id: number;
  to_shot_id: number;
  issue_type: string;
  severity: string;
  description: string;
  suggestion?: string | null;
  resolved: boolean
}

interface ShotsResponse {
  list: ShotData[];
  continuity_issues: ContinuityIssue[]
}

interface ShotForm {
  title: string;
  scene: string;
  camera: string;
  action: string;
  dialogue: string;
  prompt: string;
  duration: number;
  status: string
}

const shots = ref<ShotData[]>([])
const continuityIssues = ref<ContinuityIssue[]>([])
const currentShot = ref<number | null>(null)
const form = ref<ShotForm>({
  title: '',
  scene: '',
  camera: '',
  action: '',
  dialogue: '',
  prompt: '',
  duration: 3,
  status: 'DRAFT'
})
const saving = ref(false)
const resolving = ref(false)
const assistantMessage = ref('我是分镜导演助手。可以检查景别变化、轴线、动作连续性，并把每个镜头整理为视频模型提示词。')
const assistantSuggestions = ['优化镜头节奏', '检查轴线连续性', '补全运镜提示词']
const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  READY: '已完成',
  NEEDS_ATTENTION: '待优化',
  GENERATING: '生成中',
  GENERATED: '已生成',
  APPROVED: '已通过'
}
const shotTone = (status: string) => ['READY', 'GENERATED', 'APPROVED'].includes(status) ? 'done' : 'wait'
const shotMeta = (shot: ShotData) => [...[shot.location, shot.time_of_day, shot.interior_exterior].filter(Boolean), `${Number(shot.duration_seconds || 0).toFixed(1)}s`].join(' · ')

function applyResponse(data: ShotsResponse) {
  shots.value = data.list || []
  continuityIssues.value = data.continuity_issues || []
  if (currentShot.value == null && shots.value.length) selectShot(shots.value[0])
  else if (currentShot.value != null) {
    const selected = shots.value.find((shot) => shot.id === currentShot.value)
    if (selected) selectShot(selected)
    else if (shots.value.length) selectShot(shots.value[0])
  }
}

async function loadShots() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  try {
    applyResponse(await http.get<ShotsResponse>(`/projects/${projectId}/shots`))
  } catch (error) {
    message.error(error instanceof Error ? error.message : '分镜加载失败')
  }
}

function selectShot(shot: ShotData) {
  currentShot.value = shot.id
  form.value = {
    title: shot.title,
    scene: [shot.location, shot.time_of_day, shot.interior_exterior].filter(Boolean).join(' · '),
    camera: [shot.shot_size, shot.camera_movement].filter(Boolean).join('，'),
    action: shot.action || '',
    dialogue: shot.dialogue || '',
    prompt: shot.prompt || '',
    duration: shot.duration_seconds || 0,
    status: shot.status
  }
}

function splitScene(scene: string) {
  const parts = scene.split('·').map((part) => part.trim()).filter(Boolean)
  return {location: parts[0] || null, time_of_day: parts[1] || null, interior_exterior: parts[2] || null}
}

function splitCamera(camera: string) {
  const parts = camera.split(/[，,]/).map((part) => part.trim()).filter(Boolean)
  return {shot_size: parts[0] || null, camera_movement: parts.slice(1).join('，') || null}
}

async function persistShot() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0 || !form.value.title.trim()) return false
  const scene = splitScene(form.value.scene)
  const camera = splitCamera(form.value.camera)
  const payload = {
    title: form.value.title.trim(), ...scene, ...camera,
    action: form.value.action,
    dialogue: form.value.dialogue,
    prompt: form.value.prompt,
    duration_seconds: Number(form.value.duration) || 0,
    status: form.value.status
  }
  try {
    if (currentShot.value == null) await http.post(`/projects/${projectId}/shots`, payload)
    else await http.put(`/projects/${projectId}/shots/${currentShot.value}`, payload)
    await loadShots()
    return true
  } catch (error) {
    message.error(error instanceof Error ? error.message : '分镜保存失败');
    return false
  }
}

async function saveDraft() {
  saving.value = true
  try {
    if (await persistShot()) {
      await loadProjects();
      message.success('分镜草稿已保存')
    }
  } finally {
    saving.value = false
  }
}

async function addShot() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  try {
    const created = await http.post<{ id: number }>(`/projects/${projectId}/shots`, {
      title: '新镜头',
      duration_seconds: 3,
      status: 'DRAFT'
    });
    await loadShots();
    const shot = shots.value.find((item) => item.id === created.id);
    if (shot) selectShot(shot);
    message.success('已新增镜头')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '镜头创建失败')
  }
}

async function resolveIssues() {
  const projectId = Number(activeProject.value?.id)
  if (!Number.isInteger(projectId) || projectId <= 0) return
  resolving.value = true
  try {
    await http.post(`/projects/${projectId}/shots/continuity/resolve`);
    await loadShots();
    message.success('连续性问题已修复')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '连续性问题处理失败')
  } finally {
    resolving.value = false
  }
}

function updateAssistant(value: string) {
  assistantMessage.value = `收到“${value}”，我会结合当前分镜给出调度建议。`
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch { /* cached workspace */
  }
  await loadShots()
})
watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) {
    currentShot.value = null;
    loadShots()
  }
})
</script>

<style scoped>
.shots-panel :deep(.n-form-item) {
  margin-bottom: 4px;
}

.shots-panel :deep(.n-form-item-label) {
  color: #7d899d;
  font-size: 11px;
}

@media (max-width: 640px) {
  .shots-panel :deep(.n-card__content) {
    padding-right: 14px !important;
    padding-left: 14px !important;
  }
}
</style>
