<template>
  <main class="graph-shell">
    <header class="graph-header">
      <div class="flex min-w-0 items-center gap-3">
        <NuxtLink to="/graph" class="brand">VIDEO WORKSPACE</NuxtLink>
        <span class="divider" aria-hidden="true"></span>
        <div class="min-w-0">
          <p class="eyebrow">GRAPH CANVAS · {{ activeProject.code }}</p>
          <h1 class="truncate">{{ activeProject.name }} 工作流</h1>
        </div>
      </div>
      <div class="header-actions">
        <span class="save-state"><span class="status-dot"></span>{{ saved ? '已保存' : '未保存更改' }}</span>
        <n-button secondary size="small" @click="saveCanvas">保存画布</n-button>
        <n-button type="primary" color="#6654ee" size="small" @click="runWorkflow">运行工作流</n-button>
      </div>
    </header>

    <div class="graph-body">
      <aside class="node-library">
        <div class="library-title"><span>节点库</span><n-button text size="small" aria-label="搜索节点">⌕</n-button></div>
        <n-input v-model:value="nodeSearch" size="small" placeholder="搜索节点" clearable />
        <div class="library-section">
          <span class="section-label">工作流节点</span>
          <button v-for="item in filteredLibrary" :key="item.type" type="button" class="library-item" @click="addNode(item)">
            <span class="library-icon" :style="{ background: item.color }">{{ item.icon }}</span>
            <span><strong>{{ item.title }}</strong><small>{{ item.type }}</small></span>
            <span class="add-mark">+</span>
          </button>
        </div>
        <div class="library-footer"><span class="status-dot green"></span>画布同步正常</div>
      </aside>

      <section ref="viewport" class="canvas-viewport" @pointerdown="startPan" @pointermove="pan" @pointerup="endPan" @pointercancel="endPan" @pointerleave="endPan" @wheel.prevent="zoomCanvas">
        <div class="canvas-toolbar">
          <n-button-group size="small">
            <n-button aria-label="缩小" @click="changeZoom(-0.1)">−</n-button>
            <n-button aria-label="放大" @click="changeZoom(0.1)">+</n-button>
          </n-button-group>
          <n-button size="small" secondary @click="fitView">适应画布</n-button>
          <n-button size="small" secondary @click="addGroup">＋ 分组</n-button>
        </div>

        <div class="canvas-stage" :style="stageStyle">
          <svg class="link-layer" :width="stageSize.width" :height="stageSize.height" aria-hidden="true">
            <defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0,0 L8,4 L0,8 z" fill="#9a92e8" /></marker></defs>
            <path v-for="link in links" :key="link.id" :d="linkPath(link)" fill="none" stroke="#9a92e8" stroke-width="2" marker-end="url(#arrow)" />
          </svg>
          <div v-for="group in groups" :key="group.id" class="canvas-group" :style="groupStyle(group)"><span>{{ group.title }}</span></div>
          <article v-for="node in nodes" :key="node.id" class="graph-node" :class="{ selected: selectedId === node.id }" :style="nodeStyle(node)" @pointerdown.stop="startNodeDrag($event, node)" @click.stop="selectedId = node.id">
            <div class="node-head" :style="{ background: node.color }"><span class="node-kind">{{ node.icon }}</span><strong>{{ node.title }}</strong><button type="button" class="node-menu" aria-label="节点菜单" @click.stop="removeNode(node.id)">×</button></div>
            <div class="node-body">
              <div v-for="input in node.inputs" :key="input.name" class="port-row"><span class="port input-port"></span><span>{{ input.name }}</span><small>{{ input.value }}</small></div>
              <div v-for="output in node.outputs" :key="output.name" class="port-row output-row"><small>{{ output.type }}</small><span>{{ output.name }}</span><span class="port output-port"></span></div>
            </div>
          </article>
        </div>

        <div class="canvas-help"><span>滚轮缩放</span><span>按住空格拖动画布</span><span>{{ nodes.length }} 个节点 · {{ links.length }} 条连线</span></div>
      </section>

      <aside class="inspector">
        <div class="inspector-head"><div><span class="eyebrow">INSPECTOR</span><h2>{{ selectedNode ? selectedNode.title : '画布属性' }}</h2></div><n-button text aria-label="关闭属性面板" @click="selectedId = null">×</n-button></div>
        <template v-if="selectedNode">
          <div class="inspector-content">
            <n-form label-placement="top" size="small">
              <n-form-item label="节点标题"><n-input v-model:value="selectedNode.title" @update:value="markDirty" /></n-form-item>
              <n-form-item label="节点类型"><n-input :value="selectedNode.type" readonly /></n-form-item>
              <n-form-item label="执行模式"><n-select v-model:value="selectedNode.mode" :options="modeOptions" @update:value="markDirty" /></n-form-item>
              <n-form-item label="节点备注"><n-input v-model:value="selectedNode.note" type="textarea" :rows="3" placeholder="添加这个节点的制作备注" @update:value="markDirty" /></n-form-item>
            </n-form>
            <div class="inspector-meta"><div><span>位置</span><strong>{{ Math.round(selectedNode.x) }}, {{ Math.round(selectedNode.y) }}</strong></div><div><span>尺寸</span><strong>{{ selectedNode.width }} × {{ selectedNode.height }}</strong></div><div><span>执行顺序</span><strong>#{{ selectedNode.order }}</strong></div></div>
          </div>
        </template>
        <div v-else class="empty-inspector"><span class="empty-icon">⌘</span><strong>选择一个节点</strong><p>点击画布中的节点查看属性和输入输出。</p></div>
      </aside>
    </div>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'

const message = useMessage()
const { activeProject } = useProjectWorkspace()
const nodeSearch = ref('')
const viewport = ref(null)
const selectedId = ref('ksampler')
const zoom = ref(0.82)
const panOffset = ref({ x: 0, y: 0 })
const saved = ref(true)
const isPanning = ref(false)
const panStart = ref({ x: 0, y: 0 })
const nodeDrag = ref(null)
const stageSize = { width: 1600, height: 980 }

const nodes = ref([
  { id: 'load', type: 'LoadImage', title: '加载参考图', icon: '▧', color: '#5f68d9', x: 90, y: 205, width: 220, height: 156, order: 1, mode: '启用', note: '', inputs: [{ name: 'image', value: '雨夜车站.png' }], outputs: [{ name: 'IMAGE', type: '图像' }, { name: 'MASK', type: '遮罩' }] },
  { id: 'clip', type: 'CLIPTextEncode', title: '场景提示词', icon: 'T', color: '#3e9c9a', x: 390, y: 110, width: 240, height: 184, order: 2, mode: '启用', note: '', inputs: [{ name: 'text', value: '雨夜车站，蓝紫霓虹' }], outputs: [{ name: 'CONDITIONING', type: '条件' }] },
  { id: 'vae', type: 'VAEDecode', title: '解码图像', icon: '◈', color: '#be7b4d', x: 745, y: 410, width: 220, height: 150, order: 5, mode: '启用', note: '', inputs: [{ name: 'samples', value: 'latent' }, { name: 'vae', value: 'VAE' }], outputs: [{ name: 'IMAGE', type: '图像' }] },
  { id: 'ksampler', type: 'KSampler', title: '采样器 · 镜头 01', icon: '✦', color: '#6654ee', x: 715, y: 105, width: 255, height: 232, order: 4, mode: '启用', note: '保持角色服装和色调一致', inputs: [{ name: 'model', value: '模型' }, { name: 'positive', value: '场景提示词' }, { name: 'latent', value: '空 Latent' }], outputs: [{ name: 'LATENT', type: '潜空间' }] },
  { id: 'save', type: 'SaveImage', title: '保存成片帧', icon: '▣', color: '#4d8b65', x: 1075, y: 415, width: 220, height: 150, order: 6, mode: '启用', note: '', inputs: [{ name: 'images', value: '解码图像' }], outputs: [] }
])
const links = ref([
  { id: 1, from: 'load', fromIndex: 0, to: 'ksampler', toIndex: 1 },
  { id: 2, from: 'clip', fromIndex: 0, to: 'ksampler', toIndex: 1 },
  { id: 3, from: 'ksampler', fromIndex: 0, to: 'vae', toIndex: 0 },
  { id: 4, from: 'vae', fromIndex: 0, to: 'save', toIndex: 0 }
])
const groups = ref([{ id: 'group-1', title: '镜头 01 · 雨夜车站', x: 55, y: 60, width: 970, height: 570 }])
const library = [
  { type: 'LoadImage', title: '加载图像', icon: '▧', color: '#5f68d9' },
  { type: 'CLIPTextEncode', title: '文本提示词', icon: 'T', color: '#3e9c9a' },
  { type: 'KSampler', title: '采样器', icon: '✦', color: '#6654ee' },
  { type: 'VAEDecode', title: 'VAE 解码', icon: '◈', color: '#be7b4d' },
  { type: 'SaveImage', title: '保存图像', icon: '▣', color: '#4d8b65' }
]
const filteredLibrary = computed(() => library.filter((item) => !nodeSearch.value || `${item.title}${item.type}`.toLowerCase().includes(nodeSearch.value.toLowerCase())))
const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedId.value))
const modeOptions = [{ label: '启用', value: '启用' }, { label: '旁路', value: '旁路' }, { label: '禁用', value: '禁用' }]
const stageStyle = computed(() => ({ transform: `translate(${panOffset.value.x}px, ${panOffset.value.y}px) scale(${zoom.value})` }))

function nodeStyle(node) { return { left: `${node.x}px`, top: `${node.y}px`, width: `${node.width}px` } }
function groupStyle(group) { return { left: `${group.x}px`, top: `${group.y}px`, width: `${group.width}px`, height: `${group.height}px` } }
function portPoint(node, kind, index) { const y = 49 + 31 + index * 24; return { x: kind === 'out' ? node.x + node.width : node.x, y: node.y + y } }
function linkPath(link) { const from = nodes.value.find((node) => node.id === link.from); const to = nodes.value.find((node) => node.id === link.to); if (!from || !to) return ''; const a = portPoint(from, 'out', link.fromIndex); const b = portPoint(to, 'in', link.toIndex); const bend = Math.max(50, Math.abs(b.x - a.x) * 0.45); return `M ${a.x} ${a.y} C ${a.x + bend} ${a.y}, ${b.x - bend} ${b.y}, ${b.x} ${b.y}` }
function changeZoom(delta) { zoom.value = Math.min(1.5, Math.max(0.45, +(zoom.value + delta).toFixed(2))) }
function resetView() { zoom.value = 0.82; panOffset.value = { x: 0, y: 0 } }
function fitView() { zoom.value = 0.65; panOffset.value = { x: 30, y: 20 } }
function startPan(event) { if (event.target.closest('.graph-node, .canvas-toolbar, button')) return; isPanning.value = true; panStart.value = { x: event.clientX - panOffset.value.x, y: event.clientY - panOffset.value.y }; viewport.value?.setPointerCapture?.(event.pointerId) }
function pan(event) {
  if (nodeDrag.value) {
    const node = nodes.value.find((item) => item.id === nodeDrag.value.id)
    if (node) {
      node.x = Math.max(0, nodeDrag.value.originX + (event.clientX - nodeDrag.value.startX) / zoom.value)
      node.y = Math.max(0, nodeDrag.value.originY + (event.clientY - nodeDrag.value.startY) / zoom.value)
      markDirty()
    }
    return
  }
  if (isPanning.value) panOffset.value = { x: event.clientX - panStart.value.x, y: event.clientY - panStart.value.y }
}
function endPan(event) { isPanning.value = false; nodeDrag.value = null; if (event?.pointerId != null && viewport.value?.hasPointerCapture?.(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId) }
function zoomCanvas(event) { changeZoom(event.deltaY > 0 ? -0.05 : 0.05) }
function startNodeDrag(event, node) { selectedId.value = node.id; nodeDrag.value = { id: node.id, startX: event.clientX, startY: event.clientY, originX: node.x, originY: node.y }; viewport.value?.setPointerCapture?.(event.pointerId) }
function addNode(item) { const id = `${item.type.toLowerCase()}-${Date.now()}`; nodes.value.push({ id, type: item.type, title: item.title, icon: item.icon, color: item.color, x: 180 + (nodes.value.length % 3) * 280, y: 700 + Math.floor(nodes.value.length / 3) * 220, width: 220, height: 150, order: nodes.value.length + 1, mode: '启用', note: '', inputs: [{ name: '输入', value: '待连接' }], outputs: [{ name: '输出', type: '数据' }] }); selectedId.value = id; markDirty(); message.success(`已添加${item.title}节点`) }
function removeNode(id) { nodes.value = nodes.value.filter((node) => node.id !== id); links.value = links.value.filter((link) => link.from !== id && link.to !== id); if (selectedId.value === id) selectedId.value = null; markDirty() }
function addGroup() { groups.value.push({ id: `group-${Date.now()}`, title: '新建分组', x: 80, y: 680, width: 700, height: 230 }); markDirty() }
function markDirty() { saved.value = false }
function saveCanvas() { saved.value = true; message.success('画布已保存') }
function runWorkflow() { message.success('工作流已加入执行队列') }
</script>

<style scoped>
.graph-shell { min-height: calc(100vh - 1px); background: #f5f6fa; color: #20283b; }
.graph-header { height: 64px; display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 0 22px; background: #fff; border-bottom: 1px solid #e4e7ee; }
.brand { color: #5c52ee; font-size: 11px; font-weight: 800; letter-spacing: .8px; }
.divider { width: 1px; height: 26px; background: #e3e6ed; }
.eyebrow { margin: 0 0 4px; color: #8c96a8; font-size: 10px; letter-spacing: .7px; }
.graph-header h1 { margin: 0; font-size: 16px; font-weight: 600; }
.header-actions { display: flex; align-items: center; gap: 9px; }
.save-state { display: flex; align-items: center; gap: 6px; margin-right: 5px; color: #8b95a8; font-size: 11px; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; background: #e3a53c; display: inline-block; }
.status-dot.green { background: #49b788; }
.graph-body { display: grid; grid-template-columns: 220px minmax(0, 1fr) 270px; height: calc(100vh - 65px); min-height: 650px; }
.node-library, .inspector { background: #fff; }
.node-library { border-right: 1px solid #e1e5ed; padding: 18px 13px; }
.library-title { display: flex; justify-content: space-between; align-items: center; margin-bottom: 13px; font-size: 13px; font-weight: 700; }
.library-section { margin-top: 20px; }
.section-label { display: block; margin: 0 7px 9px; color: #99a2b2; font-size: 10px; }
.library-item { width: 100%; display: flex; align-items: center; gap: 9px; padding: 9px 7px; border: 0; border-radius: 8px; background: transparent; color: #4e596c; text-align: left; cursor: pointer; }
.library-item:hover { background: #f2f0ff; }
.library-icon { width: 27px; height: 27px; display: grid; place-items: center; border-radius: 7px; color: white; font-size: 13px; }
.library-item strong, .library-item small { display: block; }
.library-item strong { font-size: 11px; font-weight: 600; }
.library-item small { margin-top: 2px; color: #9ca5b4; font-size: 9px; }
.add-mark { margin-left: auto; color: #b5bdcb; font-size: 17px; }
.library-footer { position: absolute; bottom: 18px; display: flex; align-items: center; gap: 7px; color: #9aa3b2; font-size: 10px; }
.canvas-viewport { position: relative; overflow: hidden; background-color: #f7f8fb; background-image: radial-gradient(#d7dbe5 1px, transparent 1px); background-size: 22px 22px; cursor: grab; touch-action: none; }
.canvas-viewport:active { cursor: grabbing; }
.canvas-toolbar { position: absolute; z-index: 20; top: 14px; left: 16px; display: flex; gap: 8px; }
.zoom-value { min-width: 54px; }
.canvas-stage { position: absolute; width: 1600px; height: 980px; transform-origin: 0 0; }
.link-layer { position: absolute; inset: 0; overflow: visible; pointer-events: none; }
.canvas-group { position: absolute; border: 1px solid #cfcaff; border-radius: 12px; background: rgba(239, 237, 255, .28); pointer-events: none; }
.canvas-group span { position: absolute; top: -10px; left: 14px; padding: 2px 8px; border-radius: 5px; background: #e9e6ff; color: #6a60d7; font-size: 10px; }
.graph-node { position: absolute; overflow: hidden; border: 1px solid #d9dce5; border-radius: 9px; background: #fff; box-shadow: 0 5px 16px rgba(55, 62, 93, .1); user-select: none; cursor: move; }
.graph-node.selected { border-color: #7268ef; box-shadow: 0 0 0 2px rgba(114, 104, 239, .22), 0 8px 22px rgba(65, 56, 140, .14); }
.node-head { display: flex; align-items: center; gap: 8px; height: 38px; padding: 0 10px; color: #fff; }
.node-kind { font-size: 14px; opacity: .9; }
.node-head strong { font-size: 11px; font-weight: 600; }
.node-menu { margin-left: auto; border: 0; background: transparent; color: rgba(255,255,255,.75); font-size: 17px; cursor: pointer; }
.node-body { padding: 10px 9px; }
.port-row { position: relative; display: flex; align-items: center; gap: 7px; min-height: 27px; color: #667186; font-size: 10px; }
.port-row small { margin-left: auto; max-width: 120px; overflow: hidden; color: #a0a8b6; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.output-row { justify-content: flex-end; }
.output-row small { margin: 0 auto 0 0; }
.port { width: 9px; height: 9px; flex: 0 0 auto; border: 2px solid #fff; border-radius: 50%; box-shadow: 0 0 0 1px #9a92e8; background: #b4aff5; }
.input-port { margin-left: -14px; }
.output-port { margin-right: -14px; }
.canvas-help { position: absolute; right: 16px; bottom: 13px; display: flex; gap: 14px; color: #9ba4b3; font-size: 10px; }
.inspector { border-left: 1px solid #e1e5ed; }
.inspector-head { display: flex; justify-content: space-between; padding: 19px 16px 15px; border-bottom: 1px solid #e8eaf0; }
.inspector-head h2 { margin: 3px 0 0; font-size: 14px; font-weight: 600; }
.inspector-content { padding: 18px 16px; }
.inspector-meta { margin-top: 20px; padding-top: 14px; border-top: 1px solid #eceef3; }
.inspector-meta div { display: flex; justify-content: space-between; margin-top: 9px; color: #97a0b0; font-size: 10px; }
.inspector-meta strong { color: #556074; font-size: 10px; font-weight: 600; }
.empty-inspector { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 70%; padding: 25px; text-align: center; color: #9aa3b2; }
.empty-icon { width: 42px; height: 42px; display: grid; place-items: center; margin-bottom: 13px; border-radius: 11px; background: #f1efff; color: #6d63e6; font-size: 19px; }
.empty-inspector strong { color: #5b6578; font-size: 12px; }
.empty-inspector p { margin: 8px 0 0; font-size: 10px; line-height: 1.6; }
@media (max-width: 900px) { .graph-body { grid-template-columns: 190px minmax(0, 1fr); } .inspector { display: none; } }
@media (max-width: 640px) { .graph-header { padding: 0 12px; } .brand, .save-state, .header-actions .n-button:first-of-type { display: none; } .graph-body { grid-template-columns: 1fr; } .node-library { display: none; } .canvas-help { display: none; } }
</style>
