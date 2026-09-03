<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">03 / 07 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-5 sm:flex-row sm:items-end">
          <div>
            <h1 class="m-0 text-[31px] font-medium leading-[1.18]">每集资产管理</h1>
            <p class="mt-2 text-[13px] text-[#8a94aa]">角色、场景背景、道具与服饰按当前剧集独立维护。</p>
            <p v-if="hasEpisode && activeEpisode" class="mt-2 text-xs text-[#6654ee]">当前剧集：{{ activeEpisode.title }}</p>
          </div>
          <div class="flex w-full gap-2 sm:w-auto">
            <n-button :loading="saving" class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿</n-button>
            <n-button v-if="hasEpisode" class="!h-[42px] !rounded-[10px] !border-[#ff9b4a] !text-[#e87517]" ghost @click="openExtraction">AI 提取资产</n-button>
            <n-button class="!h-[42px] !rounded-[10px] !border-[#665bff] !text-[#5b4ff0]" ghost @click="openCreate">添加资产</n-button>
          </div>
        </div>
      </header>
      <ProductionSteps active="assets" />
      <section class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="asset-panel !rounded-[17px] !border-[#dfe3eb]">
          <div class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">{{ hasEpisode ? '本集资产清单' : '项目资产清单' }}</h2><p class="mt-2 text-xs text-[#8994a8]">编辑后即时保存，状态用于追踪资产准备进度。</p></div>
            <n-button v-if="hasEpisode" class="!rounded-[10px] !border-[#ffcf9f] !text-[#e87517]" ghost @click="openExtraction">提取场景/角色/道具</n-button>
            <n-button class="!rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="openCreate">＋ 添加资产</n-button>
          </div>
          <div class="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <n-button-group class="max-w-full overflow-x-auto"><n-button v-for="filter in filters" :key="filter.key" :type="selectedFilter === filter.key ? 'primary' : 'default'" :color="selectedFilter === filter.key ? '#6654ee' : undefined" @click="selectedFilter = filter.key">{{ filter.label }}</n-button></n-button-group>
            <n-input v-model:value="search" class="w-full sm:!w-[190px]" clearable placeholder="搜索名称或描述" />
          </div>
          <div v-if="loading" class="py-16 text-center text-sm text-[#929bad]">正在加载资产...</div>
          <div v-else-if="!filteredAssets.length" class="border border-dashed border-[#dfe3eb] px-5 py-16 text-center text-sm text-[#929bad]">暂无资产，点击“添加资产”开始维护。</div>
          <div v-else class="grid grid-cols-1 gap-[14px] sm:grid-cols-2">
            <n-card v-for="asset in filteredAssets" :key="asset.id" :content-style="{ padding: 0 }" class="min-h-[174px] !overflow-hidden !rounded-xl !border-[#dfe3eb]">
              <div class="flex min-h-[174px]"><div :style="{ background: colorByType[asset.type] || colorByType.PROP }" class="flex w-[106px] shrink-0 flex-col items-center justify-center text-white"><n-tag :bordered="false" class="self-start !ml-2 !bg-[rgba(31,40,59,0.26)] !text-white" size="small">{{ typeLabel[asset.type] }}</n-tag><strong class="mt-3 text-[32px] leading-none">{{ asset.name.slice(0, 1) }}</strong></div><div class="min-w-0 flex-1 px-3 pb-3 pt-3"><div class="flex items-start justify-between gap-2"><strong class="truncate text-[13px] font-medium">{{ asset.name }}</strong><n-tag size="small" :type="asset.status === 'LOCKED' || asset.status === 'READY' ? 'success' : 'warning'">{{ statusLabel[asset.status] }}</n-tag></div><p class="mt-2 line-clamp-2 min-h-[32px] text-[11px] text-[#7e899e]">{{ asset.description || '暂无描述' }}</p><small class="mt-2 block truncate text-[10px] text-[#929bad]">{{ asset.reference_url || '未添加参考图链接' }}</small><div class="mt-3 flex gap-2"><n-button size="tiny" class="!text-[#5a4ef0]" text @click="openEdit(asset)">编辑详情</n-button><n-button size="tiny" class="!text-[#a34d4d]" text @click="removeAsset(asset)">删除</n-button></div></div></div>
            </n-card>
          </div>
          <n-alert v-if="assets.length" :show-icon="false" class="mt-4 !rounded-[10px] !border-[#f3dfb5] !bg-[#fff9ed]" type="warning"><strong class="text-xs text-[#ae7620]">资产完整度 {{ completeness }}%</strong><span class="ml-3 text-[11px] text-[#9a7b48]">{{ completeness === 100 ? '本集资产已全部就绪。' : '仍有资产处于草稿或待生成状态。' }}</span></n-alert>
        </n-card>
        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="资产清单助手" @send="updateAssistant" />
      </section>
      <n-modal v-model:show="extractionVisible" preset="card" title="提取场景/角色/道具" class="w-[min(94vw,620px)]" :mask-closable="false">
        <p class="-mt-2 text-xs text-[#8a94aa]">根据剧本内容，AI 将按选择的模型分别提取资产。</p>
        <div class="mt-4 space-y-3">
          <div v-for="job in extractionJobs" :key="job.type" class="rounded-[12px] border border-[#ffb36d] bg-[#fff9f1] p-3.5">
            <div class="flex items-start gap-3">
              <n-checkbox v-model:checked="job.enabled" class="mt-1" />
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-start justify-between gap-2">
                  <div><span class="text-[11px] text-[#8a94aa]">{{ job.agentLabel }}</span><strong class="mt-0.5 block text-sm text-[#20283b]">{{ job.label }}</strong></div>
                  <span class="text-[11px] text-[#e87517]">3 积分</span>
                </div>
                <p class="mt-2 text-[11px] leading-5 text-[#7e899e]">{{ job.description }}</p>
                <div class="mt-3 border-t border-[#f2dec8] pt-3">
                  <label class="mb-1.5 block text-[11px] text-[#8994a8]">选择模型</label>
                  <n-select v-model:value="job.modelKey" :options="modelOptions" :disabled="!modelOptions.length" placeholder="请先配置模型" size="small" />
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-if="extractionError" class="mt-4 rounded-[9px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]" role="alert">
          <strong class="block font-medium">提取失败：{{ extractionError.message }}</strong>
          <div class="mt-1.5 grid grid-cols-1 gap-1 text-[11px] text-[#8e6262] sm:grid-cols-2">
            <span>错误代码：{{ extractionError.error_code ?? '未返回' }}</span><span>服务提供商：{{ extractionError.provider || '未返回' }}</span>
            <span class="sm:col-span-2">请求 ID：{{ extractionError.request_id || '未返回' }}</span>
          </div>
          <pre v-if="extractionError.response_content" class="mt-2 max-h-28 overflow-auto whitespace-pre-wrap break-words rounded-md bg-white px-2 py-1.5 text-[10px] leading-4 text-[#765858]">{{ extractionError.response_content }}</pre>
        </div>
        <template #footer>
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><span class="text-xs text-[#8994a8]">预估消耗：{{ selectedExtractionCount * 3 }} 积分</span><div class="flex justify-end gap-2"><n-button :disabled="extracting" @click="extractionVisible = false">取消</n-button><n-button color="#16a34a" type="primary" :loading="extracting" :disabled="!selectedExtractionCount || !modelOptions.length" @click="startExtraction">开始提取</n-button></div></div>
        </template>
      </n-modal>
      <n-modal v-model:show="editorVisible" preset="card" :title="editing ? '编辑资产详情' : '添加本集资产'" class="w-[min(92vw,560px)]" :mask-closable="false">
        <n-form label-placement="top">
          <n-form-item label="名称"><n-input v-model:value="draft.name" placeholder="如：林夏、雨夜街道、旧相机" /></n-form-item>
          <n-form-item label="类型"><n-select v-model:value="draft.type" :options="typeOptions" /></n-form-item>
          <n-form-item label="描述"><n-input v-model:value="draft.description" type="textarea" :autosize="{ minRows: 3, maxRows: 6 }" placeholder="外观、用途、出场要求或连续性备注" /></n-form-item>
          <n-form-item label="参考图链接"><n-input v-model:value="draft.reference_url" placeholder="可选，填写图片或素材地址" /></n-form-item>
          <n-form-item label="状态"><n-select v-model:value="draft.status" :options="statusOptions" /></n-form-item>
        </n-form>
        <template #footer><div class="flex justify-end gap-2"><n-button @click="editorVisible = false">取消</n-button><n-button type="primary" :loading="saving" @click="submitAsset">保存</n-button></div></template>
      </n-modal>
      <n-modal v-model:show="characterVisible" preset="card" class="w-[min(96vw,760px)]" :mask-closable="false">
        <template #header>
          <div class="flex min-w-0 items-baseline gap-2 text-[16px] font-medium"><span class="truncate">角色详情: {{ characterName }}</span><template v-if="characterForm.identity_title"><span class="text-[#c3c9d6]">|</span><span class="shrink-0 text-[13px] font-normal text-[#8a94aa]">{{ characterForm.identity_title }}</span></template></div>
        </template>
        <template #header-extra>
          <div class="flex items-center gap-2">
            <n-button size="small" class="!rounded-[8px]" @click="notifyGenerationPending">去生图</n-button>
            <n-button size="small" color="#f0820d" type="primary" class="!rounded-[8px]" @click="notifyGenerationPending">训练</n-button>
          </div>
        </template>
        <div v-if="characterLoading" class="py-16 text-center text-sm text-[#929bad]">正在加载角色详情...</div>
        <div v-else class="space-y-5">
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">基本信息</h3>
            <div class="mt-2.5 flex flex-wrap items-center gap-2">
              <span class="text-xs text-[#8994a8]">卡片：</span>
              <n-input v-model:value="characterForm.role_tag" size="small" placeholder="角色定位" class="tag-input tag-primary !w-[76px]" />
              <n-input v-model:value="characterForm.gender" size="small" placeholder="性别" class="tag-input !w-[60px]" />
              <n-input v-model:value="characterForm.age_stage" size="small" placeholder="年龄段" class="tag-input !w-[84px]" />
              <n-input v-model:value="characterForm.age_note" size="small" placeholder="年龄备注" class="tag-input !w-[84px]" />
            </div>
            <div class="mt-3 flex justify-end"><n-checkbox v-model:checked="characterForm.is_real_person">该角色为真人</n-checkbox></div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">人脸 <span class="text-[11px] font-normal text-[#b6bdcc]">(Seedance 2.0)</span></h3>
            <n-input v-model:value="characterForm.face_description" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :bordered="false" placeholder="填写角色面部特征描述，供图像生成参考" class="plain-area mt-2" />
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">服装</h3>
            <n-input v-model:value="characterForm.costume_description" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" :bordered="false" placeholder="填写角色服装与造型描述，供图像生成参考" class="plain-area mt-2" />
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">三视图</h3><n-button size="small" color="#f0820d" secondary class="!rounded-[8px]" @click="notifyGenerationPending">生成三视图</n-button></div>
            <div class="mt-3 flex flex-wrap gap-5">
              <div v-for="view in threeViewSlots" :key="view.key" class="flex flex-col items-center gap-1.5">
                <div class="image-slot" :class="{ 'is-busy': uploading && uploadSlot === view.key }" @click="triggerImageUpload(view.key)">
                  <img v-if="characterImages[view.key]?.url" :src="characterImages[view.key]?.url || ''" class="h-full w-full object-cover" alt="">
                  <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                    <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="12" cy="8.2" r="3.4" /><path d="M5.5 19.2c1.1-3.2 3.6-4.8 6.5-4.8s5.4 1.6 6.5 4.8" /></svg>
                    <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
                  </div>
                  <button v-if="characterImages[view.key]" class="slot-remove" type="button" @click.stop="clearCharacterImage(view.key)">×</button>
                  <div v-if="uploading && uploadSlot === view.key" class="slot-busy-mask">上传中...</div>
                </div>
                <span class="text-[11px] text-[#8a94aa]">{{ view.label }}</span>
              </div>
            </div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">合并三视图</h3>
            <div class="image-slot image-slot--wide mt-3" :class="{ 'is-busy': uploading && uploadSlot === 'merged' }" @click="triggerImageUpload('merged')">
              <img v-if="characterImages.merged?.url" :src="characterImages.merged.url" class="h-full w-full object-contain" alt="">
              <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><circle cx="12" cy="8.2" r="3.4" /><path d="M5.5 19.2c1.1-3.2 3.6-4.8 6.5-4.8s5.4 1.6 6.5 4.8" /></svg>
                <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
              </div>
              <button v-if="characterImages.merged" class="slot-remove" type="button" @click.stop="clearCharacterImage('merged')">×</button>
              <div v-if="uploading && uploadSlot === 'merged'" class="slot-busy-mask">上传中...</div>
            </div>
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">表情图</h3><n-button size="small" color="#f0820d" secondary class="!rounded-[8px]" @click="notifyGenerationPending">生成表情图</n-button></div>
            <div v-if="!characterImages.expressions.length" class="mt-3 py-8 text-center text-[13px] text-[#b6bdcc]">暂无数据</div>
            <div v-else class="mt-3 flex flex-wrap gap-3"><img v-for="(item, index) in characterImages.expressions" :key="item.file_id || index" :src="item.url || ''" class="image-slot !h-[96px] !w-[96px] cursor-default" alt=""></div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">元素搭配</h3>
            <div class="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div v-for="field in elementFields" :key="field.key" class="rounded-[10px] border border-[#e8eaf0] bg-white px-3 py-2">
                <span class="text-xs text-[#5c6778]">{{ field.label }}</span>
                <n-input v-model:value="characterForm[field.key]" size="small" :bordered="false" placeholder="—" class="mt-0.5 !px-0" />
              </div>
            </div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">配音绑定</h3>
            <div class="mt-3 flex items-center justify-between rounded-[10px] border border-[#e8eaf0] bg-white px-3.5 py-2">
              <span class="text-[13px]" :class="characterForm.voice_asset_id ? 'text-[#20283b]' : 'text-[#b6bdcc]'">{{ voiceBindingLabel }}</span>
              <n-popselect v-model:value="characterForm.voice_asset_id" :options="voiceOptions" trigger="click">
                <n-button size="tiny" quaternary class="!text-[#5a4ef0]">选择角色</n-button>
              </n-popselect>
            </div>
          </section>
          <input ref="fileInputRef" type="file" accept="image/*" class="hidden" @change="onImageChosen">
        </div>
        <template #footer><div class="flex justify-end gap-2"><n-button @click="characterVisible = false">取消</n-button><n-button color="#f0820d" type="primary" :loading="characterSaving" @click="saveCharacterProfile">保存</n-button></div></template>
      </n-modal>
    </div>
  </main>
</template>

<script lang="ts" setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
interface AssetItem { id: number; name: string; type: string; description: string | null; reference_url: string | null; notes: string | null; status: string; updated_at: string | null }
interface AssetResponse { list: AssetItem[]; completeness: number }
interface ProviderConfig { id: number; provider: string; model_id: string; base_url: string; enabled: boolean }
interface ModelOption { label: string; value: string }
interface ExtractionError { message: string; error_code?: number | null; response_content?: string | null; provider?: string; request_id?: string }
interface ExtractionJob { type: string; label: string; agentLabel: string; description: string; enabled: boolean; modelKey: string }
interface CharacterImageRef { file_id: string | null; url: string | null }
interface CharacterProfileData {
  asset_id: number; name: string; identity_title: string | null; role_tag: string | null; gender: string | null; age_stage: string | null; age_note: string | null; is_real_person: boolean
  face_description: string | null; costume_description: string | null
  three_view_front: CharacterImageRef | null; three_view_side: CharacterImageRef | null; three_view_back: CharacterImageRef | null; three_view_merged: CharacterImageRef | null; expressions: CharacterImageRef[]
  hairstyle: string | null; costume_ref: string | null; accessory_ref: string | null; voice_asset_id: number | null; voice_asset_name: string | null
}
type CharacterImageSlot = 'front' | 'side' | 'back' | 'merged'
const message = useMessage(); const http = useHttp(); const route = useRoute(); const { activeProject, loadProjects } = useProjectWorkspace(); const { activeEpisode, loadEpisodes, loadLibrary } = useEpisodeWorkspace()
const assets = ref<AssetItem[]>([]); const loading = ref(false); const saving = ref(false); const completeness = ref(0); const search = ref(''); const selectedFilter = ref('ALL'); const editorVisible = ref(false); const editing = ref<AssetItem | null>(null)
const extractionVisible = ref(false); const extracting = ref(false); const extractionError = ref<ExtractionError | null>(null); const providers = ref<ProviderConfig[]>([]); const modelOptions = ref<ModelOption[]>([])
const extractionJobs = reactive<ExtractionJob[]>([
  { type: 'SCENE_BACKGROUND', label: '场景提取智能体', agentLabel: '场景/背景智能体', description: '提取剧本中的场景、地点、时间、光线和视觉氛围。', enabled: true, modelKey: '' },
  { type: 'CHARACTER', label: '角色提取智能体', agentLabel: '角色/发型智能体', description: '提取人物身份、外貌、性格、关系和出场要求。', enabled: true, modelKey: '' },
  { type: 'PROP', label: '道具提取智能体', agentLabel: '道具/物件智能体', description: '提取对剧情有推动作用的关键道具与重要物件。', enabled: true, modelKey: '' },
  { type: 'COSTUME', label: '服装提取智能体', agentLabel: '服装/造型智能体', description: '提取人物服装、配饰、色彩和连续性要求。', enabled: false, modelKey: '' },
])
const draft = reactive({ name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT' })
const characterVisible = ref(false); const characterLoading = ref(false); const characterSaving = ref(false); const characterAssetId = ref(0)
const characterForm = reactive({ identity_title: '', role_tag: '', gender: '', age_stage: '', age_note: '', is_real_person: false, face_description: '', costume_description: '', hairstyle: '', costume_ref: '', accessory_ref: '', voice_asset_id: 0 })
const characterImages = reactive<{ front: CharacterImageRef | null; side: CharacterImageRef | null; back: CharacterImageRef | null; merged: CharacterImageRef | null; expressions: CharacterImageRef[] }>({ front: null, side: null, back: null, merged: null, expressions: [] })
const uploadSlot = ref<CharacterImageSlot | ''>(''); const uploading = ref(false); const fileInputRef = ref<HTMLInputElement | null>(null)
const threeViewSlots: { key: CharacterImageSlot; label: string }[] = [{ key: 'front', label: '正面' }, { key: 'side', label: '侧面' }, { key: 'back', label: '背面' }]
const elementFields = [{ key: 'hairstyle', label: '发型' }, { key: 'costume_ref', label: '服饰' }, { key: 'accessory_ref', label: '配饰' }] as const
const voiceOptions = computed(() => [{ label: '未绑定', value: 0 }, ...assets.value.filter((item) => item.type === 'VOICE').map((item) => ({ label: item.name, value: item.id }))])
const characterName = computed(() => assets.value.find((item) => item.id === characterAssetId.value)?.name || '')
const voiceBindingLabel = computed(() => characterForm.voice_asset_id ? (voiceOptions.value.find((option) => option.value === characterForm.voice_asset_id)?.label || '未绑定角色') : '未绑定角色')
const filters = [{ key: 'ALL', label: '全部' }, { key: 'CHARACTER', label: '角色' }, { key: 'SCENE_BACKGROUND', label: '场景背景' }, { key: 'SCENE', label: '场景' }, { key: 'PROP', label: '道具' }, { key: 'COSTUME', label: '服饰' }, { key: 'VOICE', label: '声音' }]
const typeLabel: Record<string, string> = { CHARACTER: '角色', SCENE_BACKGROUND: '场景背景', SCENE: '场景', PROP: '道具', COSTUME: '服饰', VOICE: '声音', MUSIC: '音乐', SOUND_EFFECT: '音效' }
const statusLabel: Record<string, string> = { DRAFT: '草稿', PENDING_CONFIRMATION: '待确认', LOCKED: '已锁定', NEEDS_COPYRIGHT: '缺少版权', TO_GENERATE: '待生成', READY: '已就绪', ARCHIVED: '已归档' }
const colorByType: Record<string, string> = { CHARACTER: 'linear-gradient(135deg,#9a8bea,#6556d9)', SCENE_BACKGROUND: 'linear-gradient(135deg,#eda977,#d87947)', PROP: 'linear-gradient(135deg,#c9a66f,#a8783d)', COSTUME: 'linear-gradient(135deg,#de8da8,#b74c72)', VOICE: 'linear-gradient(135deg,#66bfa7,#278d76)', MUSIC: 'linear-gradient(135deg,#ae8bd4,#7852ad)', SOUND_EFFECT: 'linear-gradient(135deg,#79aee4,#447ab7)' }
const filteredAssets = computed(() => assets.value.filter((item) => (selectedFilter.value === 'ALL' || item.type === selectedFilter.value) && (!search.value || `${item.name}${item.description || ''}`.toLowerCase().includes(search.value.toLowerCase()))))
const assistantMessage = ref('我是资产统筹助手。可以帮助检查本集资产缺口和连续性。'); const assistantSuggestions = ['检查本集缺口', '完善角色一致性', '整理服饰清单']
const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id)); const routeEpisodeId = computed(() => Number(route.params.episodeId || '')); const episodeId = computed(() => { if (Number.isInteger(routeEpisodeId.value) && routeEpisodeId.value > 0) return routeEpisodeId.value; if (activeEpisode.value?.projectId !== String(projectId.value)) return Number.NaN; return Number(activeEpisode.value.id) }); const hasEpisode = computed(() => Number.isInteger(episodeId.value) && episodeId.value > 0); const episodePath = computed(() => hasEpisode.value ? `/projects/${projectId.value}/episodes/${episodeId.value}/assets` : `/projects/${projectId.value}/assets`)
const typeOptions = computed(() => (hasEpisode.value ? filters.filter((item) => ['CHARACTER', 'SCENE_BACKGROUND', 'PROP', 'COSTUME', 'VOICE'].includes(item.key)) : filters.filter((item) => ['CHARACTER', 'SCENE', 'PROP', 'VOICE'].includes(item.key))).map((item) => ({ label: item.label, value: item.key })).concat([{ label: '音乐', value: 'MUSIC' }, { label: '音效', value: 'SOUND_EFFECT' }]))
const statusOptions = Object.entries(statusLabel).map(([value, label]) => ({ value, label }))
const selectedExtractionCount = computed(() => extractionJobs.filter((job) => job.enabled).length)
async function loadAssets() { if (!Number.isInteger(projectId.value) || projectId.value <= 0) return; loading.value = true; try { const data = await http.get<AssetResponse>(episodePath.value); assets.value = data.list || []; completeness.value = data.completeness || 0; try { await loadLibrary(projectId.value) } catch { /* Keep the workflow list usable when the sidebar refresh is unavailable. */ } } catch (error) { message.error(error instanceof Error ? error.message : '资产加载失败') } finally { loading.value = false } }
async function loadProviders() {
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    providers.value = response.list.filter((item) => item.enabled)
    const options: ModelOption[] = []
    for (const provider of providers.value) {
      if (provider.model_id) options.push({ label: `${provider.provider} · ${provider.model_id}`, value: `${provider.id}::${provider.model_id}` })
      try {
        const formData = new FormData(); formData.append('base_url', provider.base_url); formData.append('config_id', String(provider.id))
        const models = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
        models.list.forEach((model) => { const value = `${provider.id}::${model.id}`; if (!options.some((option) => option.value === value)) options.push({ label: `${provider.provider} · ${model.id}`, value }) })
      } catch { /* Keep the configured default model when model discovery is unavailable. */ }
    }
    modelOptions.value = options
    extractionJobs.forEach((job) => { if (!job.modelKey) job.modelKey = options[0]?.value || '' })
  } catch { providers.value = []; modelOptions.value = [] }
}
function openExtraction() { extractionError.value = null; extractionJobs.forEach((job) => { if (!job.modelKey) job.modelKey = modelOptions.value[0]?.value || '' }); extractionVisible.value = true }
function openCreate() { editing.value = null; Object.assign(draft, { name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT' }); editorVisible.value = true }
function openEdit(asset: AssetItem) { if (hasEpisode.value && asset.type === 'CHARACTER') { openCharacterDetail(asset); return } editing.value = asset; Object.assign(draft, { name: asset.name, type: asset.type, description: asset.description || '', reference_url: asset.reference_url || '', notes: asset.notes || '', status: asset.status }); editorVisible.value = true }
async function submitAsset() { if (!draft.name.trim()) { message.warning('请填写资产名称'); return }; saving.value = true; try { const payload = { name: draft.name.trim(), type: draft.type, description: draft.description, reference_url: draft.reference_url || '', notes: draft.notes || '', status: draft.status }; if (editing.value) await http.put(`${episodePath.value}/${editing.value.id}`, payload); else await http.post(episodePath.value, payload); editorVisible.value = false; await loadAssets(); message.success('资产已保存') } catch (error) { message.error(error instanceof Error ? error.message : '资产保存失败') } finally { saving.value = false } }
async function removeAsset(asset: AssetItem) { try { await http.delete(`${episodePath.value}/${asset.id}`); await loadAssets(); message.success('资产已删除') } catch (error) { message.error(error instanceof Error ? error.message : '资产删除失败') } }
async function startExtraction() {
  const jobs = extractionJobs.filter((job) => job.enabled)
  if (!jobs.length) { message.warning('请至少选择一种资产类型'); return }
  if (jobs.some((job) => !job.modelKey)) { message.warning('请为每种资产选择模型'); return }
  extracting.value = true; extractionError.value = null
  try {
    for (const job of jobs) {
      const [providerConfigId, model] = job.modelKey.split('::')
      try {
        await http.post(`${episodePath.value}/extract`, { type: job.type, provider_config_id: Number(providerConfigId), model })
      } catch (error) {
        extractionError.value = extractExtractionError(error, '资产提取失败')
        throw error
      }
    }
    extractionVisible.value = false
    await loadAssets()
    message.success('资产提取完成')
  } catch (error) {
    if (!extractionError.value) message.error(error instanceof Error ? error.message : '资产提取失败')
  } finally { extracting.value = false }
}
function extractExtractionError(error: unknown, fallback: string): ExtractionError {
  const value = error as { message?: string; data?: { data?: { error?: ExtractionError }; message?: string } }
  const structured = value.data?.data?.error
  return structured ? { ...structured, message: value.data?.message || value.message || fallback } : { message: value.data?.message || value.message || fallback }
}
async function saveDraft() { saving.value = true; try { await loadAssets(); message.success('资产草稿已保存') } finally { saving.value = false } }
async function openCharacterDetail(asset: AssetItem) {
  characterAssetId.value = asset.id; characterVisible.value = true; characterLoading.value = true
  try {
    const data = await http.get<CharacterProfileData>(`${episodePath.value}/${asset.id}/character-profile`)
    applyCharacterProfile(data)
  } catch (error) {
    characterVisible.value = false
    message.error(error instanceof Error ? error.message : '角色详情加载失败')
  } finally { characterLoading.value = false }
}
function applyCharacterProfile(data: CharacterProfileData) {
  Object.assign(characterForm, { identity_title: data.identity_title || '', role_tag: data.role_tag || '', gender: data.gender || '', age_stage: data.age_stage || '', age_note: data.age_note || '', is_real_person: !!data.is_real_person, face_description: data.face_description || '', costume_description: data.costume_description || '', hairstyle: data.hairstyle || '', costume_ref: data.costume_ref || '', accessory_ref: data.accessory_ref || '', voice_asset_id: data.voice_asset_id || 0 })
  characterImages.front = data.three_view_front; characterImages.side = data.three_view_side; characterImages.back = data.three_view_back; characterImages.merged = data.three_view_merged; characterImages.expressions = data.expressions || []
}
async function saveCharacterProfile() {
  if (!characterAssetId.value) return
  characterSaving.value = true
  try {
    const payload = { identity_title: characterForm.identity_title, role_tag: characterForm.role_tag, gender: characterForm.gender, age_stage: characterForm.age_stage, age_note: characterForm.age_note, is_real_person: characterForm.is_real_person, face_description: characterForm.face_description, costume_description: characterForm.costume_description, three_view_front_file_id: characterImages.front?.file_id || '', three_view_side_file_id: characterImages.side?.file_id || '', three_view_back_file_id: characterImages.back?.file_id || '', three_view_merged_file_id: characterImages.merged?.file_id || '', expression_file_ids: characterImages.expressions.map((item) => item.file_id || '').filter(Boolean), hairstyle: characterForm.hairstyle, costume_ref: characterForm.costume_ref, accessory_ref: characterForm.accessory_ref, voice_asset_id: characterForm.voice_asset_id || null }
    const data = await http.put<CharacterProfileData, Record<string, unknown>>(`${episodePath.value}/${characterAssetId.value}/character-profile`, payload, { payloadMode: 'json' })
    applyCharacterProfile(data)
    message.success('角色详情已保存')
  } catch (error) { message.error(error instanceof Error ? error.message : '角色详情保存失败') } finally { characterSaving.value = false }
}
function triggerImageUpload(slot: CharacterImageSlot) { uploadSlot.value = slot; const input = fileInputRef.value; if (input) { input.value = ''; input.click() } }
async function onImageChosen(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; const slot = uploadSlot.value
  if (!file || !slot) return
  uploading.value = true
  try {
    const formData = new FormData(); formData.append('files', file)
    const response = await http.post<{ list: { id: string; signed_download_url: string }[] }, FormData>('/files', formData)
    const uploaded = response.list?.[0]
    if (uploaded) characterImages[slot] = { file_id: uploaded.id, url: uploaded.signed_download_url }
  } catch (error) { message.error(error instanceof Error ? error.message : '图片上传失败') } finally { uploading.value = false; uploadSlot.value = '' }
}
function clearCharacterImage(slot: CharacterImageSlot) { characterImages[slot] = null }
function notifyGenerationPending() { message.info('AI 图像生成能力即将上线') }
function updateAssistant(value: string) { assistantMessage.value = `收到“${value}”，我会整理当前剧集的执行建议。` }
onMounted(async () => { try { await loadProjects() } catch {} try { await loadEpisodes(String(projectId.value)) } catch {} await Promise.all([loadAssets(), loadProviders()]) }); watch(() => [route.params.episodeId, activeProject.value?.id], async () => { if (activeProject.value?.id) { try { await loadEpisodes(String(activeProject.value.id)) } catch {} } await loadAssets() })
</script>
<style scoped>
@media (max-width: 640px) { .asset-panel :deep(.n-card__content) { padding-left: 14px !important; padding-right: 14px !important; } }
.tag-input { border-radius: 999px !important; }
.tag-input :deep(.n-input__input-el), .tag-input :deep(.n-input__placeholder) { text-align: center; }
.tag-primary :deep(.n-input__input-el), .tag-primary :deep(.n-input__placeholder) { color: #3b6cff; }
.plain-area { background: #f7f8fa !important; border-radius: 10px !important; }
.image-slot { position: relative; width: 88px; height: 108px; flex-shrink: 0; overflow: hidden; border-radius: 10px; border: 1px dashed #d9dee8; background: #fafbfd; cursor: pointer; }
.image-slot:hover { border-color: #f0a35e; }
.image-slot--wide { width: 196px; }
.image-slot.is-busy { pointer-events: none; }
.slot-remove { position: absolute; top: 4px; right: 6px; width: 18px; height: 18px; line-height: 16px; border-radius: 999px; background: rgba(31, 40, 59, 0.55); color: #fff; font-size: 13px; text-align: center; border: none; cursor: pointer; }
.slot-busy-mask { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; background: rgba(255, 255, 255, 0.72); font-size: 11px; color: #e87517; }
</style>
