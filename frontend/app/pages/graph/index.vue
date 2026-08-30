<template>
  <main class="graph-shell">
    <header class="graph-header">
      <div class="flex min-w-0 items-center gap-3">
        <NuxtLink class="brand" :to="`/projects/${activeProject.id}/canvas`">VIDEO WORKSPACE</NuxtLink>
        <span aria-hidden="true" class="divider"></span>
        <div class="min-w-0">
          <p class="eyebrow">GRAPH CANVAS · {{ activeProject.code }}</p>
          <h1 class="truncate">{{ activeProject.name }}<span v-if="activeEpisode" class="font-normal"> · {{ activeEpisode.title }}</span> 工作流</h1>
        </div>
      </div>
      <div class="header-actions">
        <div class="autosave-control">
          <span>自动保存</span>
          <n-select v-model:value="autoSaveSeconds" :options="autoSaveOptions" aria-label="自动保存间隔" size="small"/>
        </div>
        <span :class="{ saved, saving }" class="save-state"><span :class="{ saved, saving }" class="status-dot"></span>{{
            saving ? '保存中...' : saved ? '已保存' : '未保存更改'
          }}</span>
        <n-button secondary size="small" @click="saveCanvas()">保存画布</n-button>
        <n-button color="#6654ee" size="small" type="primary" @click="runWorkflow">运行工作流</n-button>
      </div>
    </header>

    <div class="graph-body">
      <aside class="node-library">
        <div class="library-title"><span>节点库</span>
          <n-button aria-label="搜索节点" size="small" text>⌕</n-button>
        </div>
        <n-input v-model:value="nodeSearch" clearable placeholder="搜索节点" size="small"/>
        <div class="library-section">
          <span class="section-label">工作流节点</span>
          <button v-for="item in filteredLibrary" :key="item.type" class="library-item" type="button"
                  @click="addNode(item)">
            <span :style="{ background: item.color }" class="library-icon">{{ item.icon }}</span>
            <span><strong>{{ item.title }}</strong><small>{{ item.type }}</small></span>
            <span class="add-mark">+</span>
          </button>
        </div>
        <div class="library-footer"><span class="status-dot green"></span>画布同步正常</div>
      </aside>

      <section ref="viewport" class="canvas-viewport" @dblclick="cancelConnection" @pointercancel="handleViewportPointerUp"
               @pointerdown="startPan" @pointerleave="endPan" @pointermove="pan"
               @pointerup="handleViewportPointerUp" @wheel.prevent="zoomCanvas">
        <div class="canvas-toolbar">
          <n-button-group size="small">
            <n-button aria-label="缩小" @click="changeZoom(-0.1)">−</n-button>
            <n-button aria-label="放大" @click="changeZoom(0.1)">+</n-button>
          </n-button-group>
          <n-button secondary size="small" @click="fitView">适应画布</n-button>
          <n-button secondary size="small" @click="addGroup">＋ 分组</n-button>
          <span v-if="connecting" class="connection-state">连接中 · 点击或松开到{{
              connecting.side === 'right' ? '输入' : '输出'
            }}端口完成，Esc 取消</span>
          <n-button v-if="selectedLink" secondary size="small" type="error" @click="removeSelectedLink">删除连线
          </n-button>
        </div>

        <div :style="stageStyle" class="canvas-stage">
          <svg :height="stageSize.height" :width="stageSize.width" aria-hidden="true" class="link-layer">
            <defs>
              <marker id="arrow" markerHeight="8" markerWidth="8" orient="auto" refX="7" refY="4">
                <path d="M0,0 L8,4 L0,8 z" fill="#9a92e8"/>
              </marker>
            </defs>
            <path v-for="link in links" :key="`hit-${link.id}`" :d="linkPath(link)" class="link-hit-area" fill="none"
                  stroke="transparent" stroke-width="14" @pointerdown.stop @click.stop="selectedLinkId = link.id"/>
            <path v-for="link in links" :key="link.id" :class="{ selected: selectedLinkId === link.id }" :d="linkPath(link)"
                  class="canvas-link" fill="none" marker-end="url(#arrow)"
                  stroke-width="2"/>
            <path v-if="connectionPath" :d="connectionPath" class="connection-preview" fill="none" stroke="#6654ee"
                  stroke-dasharray="6 5" stroke-width="2"/>
          </svg>
          <div v-for="group in groups" :key="group.id" :style="groupStyle(group)" class="canvas-group">
            <span>{{ group.title }}</span></div>
          <article v-for="node in nodes" :key="node.id" :class="{ selected: selectedId === node.id }" :style="nodeStyle(node)"
                   class="graph-node" @wheel.stop.prevent="resizeNodeByWheel($event, node)"
                   @pointerdown.stop="startNodeDrag($event, node)" @click.stop="selectedId = node.id">
            <div :style="{ background: node.color }" class="node-head-bg">
              <div class="node-head-content"><span class="node-kind">{{ node.icon }}</span><strong
                  v-if="!isEditing(node, 'title')"
                  @dblclick.stop="beginEdit(node, 'title', undefined, node.title, $event)">{{
                  node.title
                }}</strong><input v-else ref="editInput" v-model="editText" class="node-inline-input" @blur="finishEdit"
                                  @pointerdown.stop @mousedown.stop @click.stop
                                  @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/>
                <button aria-label="节点菜单" class="node-menu" type="button" @pointerdown.stop @mousedown.stop
                        @click.stop.prevent="removeNode(node.id)">×
                </button>
              </div>
            </div>
            <div class="node-body">
              <div v-for="(input, inputIndex) in node.inputs" :key="input.name" class="port-row"><span
                  :class="portStateClass(node, 'left', inputIndex)" class="port input-port" title="拖拽到此处连接"
                  @pointerdown.stop.prevent="startPortConnection($event, node, 'left', inputIndex)"
                  @pointerup.stop.prevent="completePortConnection($event, node, 'left', inputIndex)"></span><span
                  v-if="!isEditing(node, 'input-name', inputIndex)"
                  @dblclick.stop="beginEdit(node, 'input-name', inputIndex, input.name, $event)">{{ input.name }}</span><input
                  v-else ref="editInput" v-model="editText" class="node-inline-input" @blur="finishEdit" @pointerdown.stop
                  @mousedown.stop @click.stop @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/><small
                  v-if="!isEditing(node, 'input-value', inputIndex)"
                  @dblclick.stop="beginEdit(node, 'input-value', inputIndex, input.value, $event)">{{
                  input.value
                }}</small><input v-else ref="editInput" v-model="editText" class="node-inline-input node-value-input"
                                 @blur="finishEdit" @pointerdown.stop @mousedown.stop @click.stop
                                 @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/></div>
              <div v-for="(output, outputIndex) in node.outputs" :key="output.name" class="port-row output-row"><small
                  v-if="!isEditing(node, 'output-type', outputIndex)"
                  @dblclick.stop="beginEdit(node, 'output-type', outputIndex, output.type, $event)">{{
                  output.type
                }}</small><input v-else ref="editInput" v-model="editText" class="node-inline-input node-value-input"
                                 @blur="finishEdit" @pointerdown.stop @mousedown.stop @click.stop
                                 @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/><span
                  v-if="!isEditing(node, 'output-name', outputIndex)"
                  @dblclick.stop="beginEdit(node, 'output-name', outputIndex, output.name, $event)">{{
                  output.name
                }}</span><input v-else ref="editInput" v-model="editText" class="node-inline-input" @blur="finishEdit"
                                @pointerdown.stop @mousedown.stop @click.stop
                                @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/><span :class="portStateClass(node, 'right', outputIndex)"
                                                                                            class="port output-port"
                                                                                            title="拖拽到输入端口连接"
                                                                                            @pointerdown.stop.prevent="startPortConnection($event, node, 'right', outputIndex)"
                                                                                            @pointerup.stop.prevent="completePortConnection($event, node, 'right', outputIndex)"></span>
              </div>
            </div>
            <span :class="{ 'connect-source': connecting?.node === node && connecting.side === 'left', 'connect-target': isConnectionTarget(node, 'left') }"
                  aria-hidden="true"
                  class="node-resizer node-resizer-left" @pointerdown.stop="handleEdgePointerDown($event, node, 'left')"
                  @dblclick.stop.prevent="beginEdgeConnection($event, node, 'left')"></span>
            <span :class="{ 'connect-source': connecting?.node === node && connecting.side === 'right', 'connect-target': isConnectionTarget(node, 'right') }"
                  aria-hidden="true"
                  class="node-resizer node-resizer-right" @pointerdown.stop="handleEdgePointerDown($event, node, 'right')"
                  @dblclick.stop.prevent="beginEdgeConnection($event, node, 'right')"></span>
            <span aria-hidden="true" class="node-resizer node-resizer-top"
                  @pointerdown.stop="startNodeResize($event, node, 'top')"></span>
            <span aria-hidden="true" class="node-resizer node-resizer-bottom"
                  @pointerdown.stop="startNodeResize($event, node, 'bottom')"></span>
          </article>
        </div>

        <div class="canvas-help"><span>点击或拖拽圆点连接节点</span><span>点击连线可删除</span><span>{{ nodes.length }} 个节点 · {{
            links.length
          }} 条连线</span></div>
      </section>

      <aside class="inspector">
        <div class="inspector-head">
          <div><span class="eyebrow">INSPECTOR</span>
            <h2>{{ selectedNode ? selectedNode.title : '画布属性' }}</h2></div>
          <n-button aria-label="关闭属性面板" text @click="selectedId = null">×</n-button>
        </div>
        <template v-if="selectedNode">
          <div class="inspector-content">
            <n-form label-placement="top" size="small">
              <n-form-item label="节点标题">
                <n-input v-model:value="selectedNode.title" @update:value="markDirty"/>
              </n-form-item>
              <n-form-item label="标题栏颜色">
                <n-color-picker v-model:value="selectedNode.color" :modes="['hex']" :show-alpha="false"
                                @update:value="markDirty"/>
              </n-form-item>
              <n-form-item label="节点类型">
                <n-input :value="selectedNode.type" readonly/>
              </n-form-item>
              <n-form-item label="执行模式">
                <n-select v-model:value="selectedNode.mode" :options="modeOptions" @update:value="markDirty"/>
              </n-form-item>
              <n-form-item label="节点备注">
                <n-input v-model:value="selectedNode.note" :rows="3" placeholder="添加这个节点的制作备注"
                         type="textarea" @update:value="markDirty"/>
              </n-form-item>
            </n-form>
            <div class="inspector-meta">
              <div><span>位置</span><strong>{{ Math.round(selectedNode.x) }}, {{ Math.round(selectedNode.y) }}</strong>
              </div>
              <div><span>尺寸</span><strong>{{ selectedNode.width }} × {{ selectedNode.height }}</strong></div>
              <div><span>执行顺序</span><strong>#{{ selectedNode.order }}</strong></div>
            </div>
          </div>
        </template>
        <div v-else class="empty-inspector"><span class="empty-icon">⌘</span><strong>选择一个节点</strong>
          <p>点击画布中的节点查看属性和输入输出。</p></div>
      </aside>
    </div>
  </main>
</template>

<script lang="ts" setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'

const message = useMessage()
const {activeProject} = useProjectWorkspace()
const {activeEpisodeId, activeEpisode} = useEpisodeWorkspace()
const route = useRoute()
const graphWorkspace = useGraphWorkspace()
const episodeId = computed(() => String(route.params.episodeId || activeEpisodeId.value || ''))
const nodeSearch = ref('')
const viewport = ref(null)
const selectedId = ref('ksampler')
const zoom = ref(0.82)
const panOffset = ref({x: 0, y: 0})
const saved = ref(true)
const saving = ref(false)
const changeRevision = ref(0)
const autoSaveSeconds = ref(10)
const autoSaveOptions = [
  {label: '5 秒', value: 5},
  {label: '10 秒', value: 10},
  {label: '30 秒', value: 30},
]
const autoSaveStorageKey = 'video-workspace-auto-save-seconds'
const autoSaveReady = ref(false)
let autoSaveTimer: number | null = null
const isPanning = ref(false)
const panStart = ref({x: 0, y: 0})
const nodeDrag = ref(null)
const nodeResize = ref(null)
const connecting = ref(null)
const portDrag = ref(null)
const selectedLinkId = ref(null)
const connectionPointer = ref({x: 0, y: 0})
let lastConnectionCompletedAt = 0
const resizeSaveTimers = new Map()
const editing = ref(null)
const editText = ref('')
const stageSize = {width: 1600, height: 980}

const nodes = ref([
  {
    id: 'load',
    type: 'LoadImage',
    title: '加载参考图',
    icon: '▧',
    color: '#5f68d9',
    x: 90,
    y: 205,
    width: 220,
    height: 156,
    order: 1,
    mode: '启用',
    note: '',
    inputs: [{name: 'image', value: '雨夜车站.png'}],
    outputs: [{name: 'IMAGE', type: '图像'}, {name: 'MASK', type: '遮罩'}]
  },
  {
    id: 'clip',
    type: 'CLIPTextEncode',
    title: '场景提示词',
    icon: 'T',
    color: '#3e9c9a',
    x: 390,
    y: 110,
    width: 240,
    height: 184,
    order: 2,
    mode: '启用',
    note: '',
    inputs: [{name: 'text', value: '雨夜车站，蓝紫霓虹'}],
    outputs: [{name: 'CONDITIONING', type: '条件'}]
  },
  {
    id: 'vae',
    type: 'VAEDecode',
    title: '解码图像',
    icon: '◈',
    color: '#be7b4d',
    x: 745,
    y: 410,
    width: 220,
    height: 150,
    order: 5,
    mode: '启用',
    note: '',
    inputs: [{name: 'samples', value: 'latent'}, {name: 'vae', value: 'VAE'}],
    outputs: [{name: 'IMAGE', type: '图像'}]
  },
  {
    id: 'ksampler',
    type: 'KSampler',
    title: '采样器 · 镜头 01',
    icon: '✦',
    color: '#6654ee',
    x: 715,
    y: 105,
    width: 255,
    height: 232,
    order: 4,
    mode: '启用',
    note: '保持角色服装和色调一致',
    inputs: [{name: 'model', value: '模型'}, {name: 'positive', value: '场景提示词'}, {
      name: 'latent',
      value: '空 Latent'
    }],
    outputs: [{name: 'LATENT', type: '潜空间'}]
  },
  {
    id: 'save',
    type: 'SaveImage',
    title: '保存成片帧',
    icon: '▣',
    color: '#4d8b65',
    x: 1075,
    y: 415,
    width: 220,
    height: 150,
    order: 6,
    mode: '启用',
    note: '',
    inputs: [{name: 'images', value: '解码图像'}],
    outputs: []
  }
])
const links = ref([
  {id: 1, from: 'load', fromIndex: 0, to: 'ksampler', toIndex: 1},
  {id: 2, from: 'clip', fromIndex: 0, to: 'ksampler', toIndex: 1},
  {id: 3, from: 'ksampler', fromIndex: 0, to: 'vae', toIndex: 0},
  {id: 4, from: 'vae', fromIndex: 0, to: 'save', toIndex: 0}
])
const groups = ref([{id: 'group-1', title: '镜头 01 · 雨夜车站', x: 55, y: 60, width: 970, height: 570}])
const library = [
  {type: 'LoadImage', title: '加载图像', icon: '▧', color: '#5f68d9'},
  {type: 'CLIPTextEncode', title: '文本提示词', icon: 'T', color: '#3e9c9a'},
  {type: 'KSampler', title: '采样器', icon: '✦', color: '#6654ee'},
  {type: 'VAEDecode', title: 'VAE 解码', icon: '◈', color: '#be7b4d'},
  {type: 'SaveImage', title: '保存图像', icon: '▣', color: '#4d8b65'}
]
const filteredLibrary = computed(() => library.filter((item) => !nodeSearch.value || `${item.title}${item.type}`.toLowerCase().includes(nodeSearch.value.toLowerCase())))
const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedId.value))
const selectedLink = computed(() => links.value.find((link) => link.id === selectedLinkId.value))
const modeOptions = [{label: '启用', value: '启用'}, {label: '旁路', value: '旁路'}, {label: '禁用', value: '禁用'}]
const stageStyle = computed(() => ({transform: `translate(${panOffset.value.x}px, ${panOffset.value.y}px) scale(${zoom.value})`}))
const connectionPath = computed(() => {
  if (!connecting.value) return ''
  const source = portPoint(connecting.value.node, connecting.value.side === 'right' ? 'out' : 'in', connecting.value.index)
  const target = connectionPointer.value
  const bend = Math.max(50, Math.abs(target.x - source.x) * 0.45)
  return connecting.value.side === 'right'
      ? `M ${source.x} ${source.y} C ${source.x + bend} ${source.y}, ${target.x - bend} ${target.y}, ${target.x} ${target.y}`
      : `M ${target.x} ${target.y} C ${target.x + bend} ${target.y}, ${source.x - bend} ${source.y}, ${source.x} ${source.y}`
})

function handleConnectionKeydown(event) {
  if (event.key === 'Escape') {
    cancelConnection()
    selectedLinkId.value = null
    return
  }
  if ((event.key === 'Delete' || event.key === 'Backspace') && selectedLink.value && !editing.value && !event.target?.closest?.('input, textarea')) removeSelectedLink()
}

function handleWindowPointerUp() {
  if (portDrag.value) cancelConnection()
}

function cancelConnection() {
  connecting.value = null
  portDrag.value = null
  nodeResize.value = null
  nodeDrag.value = null
  isPanning.value = false
}

onMounted(async () => {
  const result = await graphWorkspace.load(activeProject.value?.id, episodeId.value)
  if (!result) return
  const loadedNodes = (result.nodes || []).map((node) => ({
    id: node.nodeKey ?? node.node_key,
    type: node.nodeType ?? node.node_type,
    title: node.title || node.nodeType || node.node_type,
    icon: '◇',
    color: node.color || '#6654ee',
    x: node.positionX ?? node.position_x ?? 0,
    y: node.positionY ?? node.position_y ?? 0,
    width: node.width || 220,
    height: node.height || 150,
    order: node.executionOrder ?? node.execution_order ?? 0,
    mode: (node.mode ?? 0) === 2 ? '旁路' : (node.mode ?? 0) === 4 ? '禁用' : '启用',
    note: '',
    inputs: Array.isArray(node.inputs) ? node.inputs.map((input) => ({
      name: input.name ?? input.label ?? '输入',
      value: input.value ?? input.widget ?? '待连接'
    })) : [],
    outputs: Array.isArray(node.outputs) ? node.outputs.map((output) => ({
      name: output.name ?? output.label ?? '输出',
      type: output.type ?? output.dtype ?? '数据'
    })) : [],
  }))
  if (loadedNodes.length) nodes.value = loadedNodes
  links.value = (result.links || []).map((link) => ({
    id: link.linkKey ?? link.link_key,
    serverId: link.id,
    from: link.originNodeKey ?? link.origin_node_key,
    fromIndex: link.originSlot ?? link.origin_slot ?? 0,
    to: link.targetNodeKey ?? link.target_node_key,
    toIndex: link.targetSlot ?? link.target_slot ?? 0
  }))
  groups.value = (result.groups || []).map((group) => ({
    id: `group-${group.id}`,
    title: group.title,
    x: group.positionX ?? group.position_x ?? 0,
    y: group.positionY ?? group.position_y ?? 0,
    width: group.width,
    height: group.height
  }))
  saved.value = true
})
onMounted(() => window.addEventListener('keydown', handleConnectionKeydown))
onMounted(() => window.addEventListener('pointerup', handleWindowPointerUp))
onMounted(() => {
  const stored = Number(window.localStorage.getItem(autoSaveStorageKey))
  if ([5, 10, 30].includes(stored)) autoSaveSeconds.value = stored
  startAutoSave()
  autoSaveReady.value = true
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleConnectionKeydown)
  window.removeEventListener('pointerup', handleWindowPointerUp)
  stopAutoSave()
})
watch(autoSaveSeconds, (seconds) => {
  window.localStorage.setItem(autoSaveStorageKey, String(seconds))
  startAutoSave()
  if (autoSaveReady.value) message.info(`自动保存间隔已设置为 ${seconds} 秒`)
})

function nodeMinWidth(node) {
  const longest = Math.max(node.title?.length || 0, ...(node.inputs || []).map((item) => `${item.name}${item.value}`.length), ...(node.outputs || []).map((item) => `${item.name}${item.type}`.length))
  return Math.max(190, Math.min(360, 120 + longest * 5.2))
}

function nodeBaseMinHeight(node) {
  return Math.max(110, 38 + 20 + ((node.inputs?.length || 0) + (node.outputs?.length || 0)) * 27)
}

function nodeMinHeight(node) {
  return nodeBaseMinHeight(node) * nodeContentScale(node)
}

function nodeRenderWidth(node) {
  return Math.max(node.width, nodeMinWidth(node))
}

function nodeRenderHeight(node) {
  return Math.max(node.height, nodeMinHeight(node))
}

function nodeContentScale(node) {
  const widthScale = Math.max(node.width, 220) / 220
  const heightScale = Math.max(node.height, 150) / 150
  return Math.min(1.6, Math.max(1, 1 + (Math.max(widthScale, heightScale) - 1) * 0.7))
}

function nodeStyle(node) {
  return {
    left: `${node.x}px`,
    top: `${node.y}px`,
    width: `${nodeRenderWidth(node)}px`,
    minHeight: `${nodeRenderHeight(node)}px`,
    '--node-scale': nodeContentScale(node)
  }
}

function resizeNodeByWheel(event, node) {
  if (editing.value || event.target?.closest?.('input, button')) return
  selectedId.value = node.id
  const factor = event.deltaY < 0 ? 1.08 : 1 / 1.08
  const currentWidth = nodeRenderWidth(node)
  const currentHeight = nodeRenderHeight(node)
  const nextWidth = Math.min(640, Math.max(nodeMinWidth(node), Math.round(currentWidth * factor)))
  const nextHeight = Math.min(560, Math.max(nodeMinHeight(node), Math.round(currentHeight * factor)))
  node.x -= (nextWidth - currentWidth) / 2
  node.y -= (nextHeight - currentHeight) / 2
  node.width = nextWidth
  node.height = nextHeight
  markDirty()
  const previousTimer = resizeSaveTimers.get(node.id)
  if (previousTimer) clearTimeout(previousTimer)
  resizeSaveTimers.set(node.id, setTimeout(async () => {
    resizeSaveTimers.delete(node.id)
    try {
      await graphWorkspace.saveNode(node)
    } catch {
      message.warning('节点尺寸已调整，但服务器保存失败')
    }
  }, 250))
}

function isEditing(node, field, index) {
  return editing.value?.node === node && editing.value.field === field && editing.value.index === index
}

function beginEdit(node, field, index, value, event) {
  event?.stopPropagation?.()
  editing.value = {node, field, index, original: value}
  editText.value = value
  nextTick(() => document.querySelector('.node-inline-input')?.focus())
}

async function finishEdit() {
  const current = editing.value
  if (!current) return
  const value = editText.value.trim()
  if (value) {
    if (current.field === 'title') current.node.title = value
    else if (current.field === 'input-name') current.node.inputs[current.index].name = value
    else if (current.field === 'input-value') current.node.inputs[current.index].value = value
    else if (current.field === 'output-name') current.node.outputs[current.index].name = value
    else if (current.field === 'output-type') current.node.outputs[current.index].type = value
    markDirty()
    try {
      await graphWorkspace.saveNode(current.node)
    } catch {
      message.warning('内容已修改，但服务器保存失败')
    }
  }
  editing.value = null
}

function cancelEdit() {
  editing.value = null
}

function groupStyle(group) {
  return {left: `${group.x}px`, top: `${group.y}px`, width: `${group.width}px`, height: `${group.height}px`}
}

function portPoint(node, kind, index) {
  const rowIndex = kind === 'out' ? node.inputs.length + index : index
  const inset = nodeRenderWidth(node) * 0.015
  const scale = nodeContentScale(node)
  const y = inset + 38 * scale + inset + 13.5 * scale + rowIndex * 27 * scale
  return {x: kind === 'out' ? node.x + nodeRenderWidth(node) : node.x, y: node.y + y}
}

function connectionSlotAt(node, side, event) {
  const count = side === 'right' ? node.outputs?.length || 0 : node.inputs?.length || 0
  if (!count) return -1
  const rect = viewport.value?.getBoundingClientRect()
  if (!rect) return 0
  const y = (event.clientY - rect.top - panOffset.value.y) / zoom.value
  const kind = side === 'right' ? 'out' : 'in'
  let nearest = 0
  let distance = Number.POSITIVE_INFINITY
  for (let index = 0; index < count; index += 1) {
    const candidate = portPoint(node, kind, index)
    const nextDistance = Math.abs(candidate.y - y)
    if (nextDistance < distance) {
      nearest = index
      distance = nextDistance
    }
  }
  return nearest
}

function isConnectionTarget(node, side) {
  if (!connecting.value || connecting.value.node === node || connecting.value.side === side) return false
  return side === 'left' ? (node.inputs?.length || 0) > 0 : (node.outputs?.length || 0) > 0
}

function portStateClass(node, side, index) {
  if (!connecting.value) return ''
  if (connecting.value.node === node && connecting.value.side === side && connecting.value.index === index) return 'connect-source'
  if (connecting.value.node !== node && connecting.value.side !== side) return 'connect-target'
  return 'connect-disabled'
}

function linkPath(link) {
  const from = nodes.value.find((node) => node.id === link.from);
  const to = nodes.value.find((node) => node.id === link.to);
  if (!from || !to) return '';
  const a = portPoint(from, 'out', link.fromIndex);
  const b = portPoint(to, 'in', link.toIndex);
  const bend = Math.max(50, Math.abs(b.x - a.x) * 0.45);
  return `M ${a.x} ${a.y} C ${a.x + bend} ${a.y}, ${b.x - bend} ${b.y}, ${b.x} ${b.y}`
}

function changeZoom(delta) {
  zoom.value = Math.min(1.5, Math.max(0.45, +(zoom.value + delta).toFixed(2)))
}

function fitView() {
  zoom.value = 0.65;
  panOffset.value = {x: 30, y: 20}
}

function startPan(event) {
  if (event.target?.closest?.('.graph-node, .canvas-toolbar, button, .link-hit-area')) return;
  selectedLinkId.value = null;
  isPanning.value = true;
  panStart.value = {x: event.clientX - panOffset.value.x, y: event.clientY - panOffset.value.y};
  viewport.value?.setPointerCapture?.(event.pointerId)
}

function pan(event) {
  if (connecting.value) {
    const rect = viewport.value?.getBoundingClientRect()
    if (rect) connectionPointer.value = {
      x: (event.clientX - rect.left - panOffset.value.x) / zoom.value,
      y: (event.clientY - rect.top - panOffset.value.y) / zoom.value
    }
    return
  }
  if (nodeResize.value) {
    const resize = nodeResize.value
    const node = resize.node
    const dx = (event.clientX - resize.startX) / zoom.value
    const dy = (event.clientY - resize.startY) / zoom.value
    const minWidth = nodeMinWidth(node)
    const minHeight = nodeMinHeight(node)
    if (resize.edge === 'left') {
      const nextWidth = Math.max(minWidth, resize.originWidth - dx)
      node.x = resize.originX + resize.originWidth - nextWidth
      node.width = nextWidth
    } else if (resize.edge === 'right') node.width = Math.max(minWidth, resize.originWidth + dx)
    else if (resize.edge === 'top') {
      const nextHeight = Math.max(minHeight, resize.originHeight - dy)
      node.y = resize.originY + resize.originHeight - nextHeight
      node.height = nextHeight
    } else node.height = Math.max(minHeight, resize.originHeight + dy)
    markDirty()
    return
  }
  if (nodeDrag.value) {
    const node = nodes.value.find((item) => item.id === nodeDrag.value.id)
    if (node) {
      node.x = Math.max(0, nodeDrag.value.originX + (event.clientX - nodeDrag.value.startX) / zoom.value)
      node.y = Math.max(0, nodeDrag.value.originY + (event.clientY - nodeDrag.value.startY) / zoom.value)
      markDirty()
    }
    return
  }
  if (isPanning.value) panOffset.value = {x: event.clientX - panStart.value.x, y: event.clientY - panStart.value.y}
}

function handleViewportPointerUp(event) {
  if (portDrag.value) {
    if (connecting.value) cancelConnection()
    portDrag.value = null
    if (event?.pointerId != null && viewport.value?.hasPointerCapture?.(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId)
    return
  }
  endPan(event)
}

function endPan(event) {
  isPanning.value = false;
  nodeDrag.value = null;
  const resizedNode = nodeResize.value?.node;
  nodeResize.value = null;
  if (event?.pointerId != null && viewport.value?.hasPointerCapture?.(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId);
  if (resizedNode) graphWorkspace.saveNode(resizedNode).catch(() => message.warning('节点尺寸已修改，但服务器保存失败'))
}

function zoomCanvas(event) {
  changeZoom(event.deltaY > 0 ? -0.05 : 0.05)
}

function startNodeDrag(event, node) {
  if (editing.value || connecting.value) return;
  selectedId.value = node.id;
  nodeDrag.value = {id: node.id, startX: event.clientX, startY: event.clientY, originX: node.x, originY: node.y};
  viewport.value?.setPointerCapture?.(event.pointerId)
}

function startNodeResize(event, node, edge) {
  if (editing.value) return
  selectedId.value = node.id
  nodeResize.value = {
    node,
    edge,
    startX: event.clientX,
    startY: event.clientY,
    originX: node.x,
    originY: node.y,
    originWidth: node.width,
    originHeight: node.height
  }
  viewport.value?.setPointerCapture?.(event.pointerId)
}

function startPortConnection(event, node, side, index) {
  if (connecting.value) {
    if (connecting.value.node === node && connecting.value.side === side && connecting.value.index === index) return
    beginEdgeConnection(event, node, side, index)
    return
  }
  portDrag.value = {pointerId: event.pointerId}
  beginEdgeConnection(event, node, side, index)
}

function completePortConnection(event, node, side, index) {
  if (!portDrag.value) return
  if (connecting.value?.node === node && connecting.value.side === side && connecting.value.index === index) {
    portDrag.value = null
    return
  }
  beginEdgeConnection(event, node, side, index)
  portDrag.value = null
  if (event?.pointerId != null && viewport.value?.hasPointerCapture?.(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId)
}

function handleEdgePointerDown(event, node, edge) {
  // Start on the second press as well as dblclick so pointer capture from the
  // first resize press cannot prevent entering connection mode.
  if (event.detail > 1) {
    event.preventDefault()
    nodeResize.value = null
    nodeDrag.value = null
    beginEdgeConnection(event, node, edge)
    return
  }
  if (connecting.value) beginEdgeConnection(event, node, edge)
  else startNodeResize(event, node, edge)
}

async function beginEdgeConnection(event, node, side, explicitIndex) {
  if (Date.now() - lastConnectionCompletedAt < 400) return
  const current = connecting.value
  if (current) {
    if (current.node === node || current.side === side) return
    const targetIndex = explicitIndex ?? connectionSlotAt(node, side, event)
    if (targetIndex < 0) {
      message.warning(`该节点没有可用的${side === 'left' ? '输入' : '输出'}端口`)
      return
    }
    const output = current.side === 'right' ? current.node : node
    const input = current.side === 'right' ? node : current.node
    const fromIndex = current.side === 'right' ? current.index : targetIndex
    const toIndex = current.side === 'right' ? targetIndex : current.index
    const duplicate = links.value.some((link) => link.from === output.id && link.fromIndex === fromIndex && link.to === input.id && link.toIndex === toIndex)
    if (duplicate) {
      message.warning('这两个端口已经存在连线')
      cancelConnection()
      return
    }
    const replaced = links.value.filter((link) => link.to === input.id && link.toIndex === toIndex)
    lastConnectionCompletedAt = Date.now()
    try {
      await Promise.all(replaced.map((link) => graphWorkspace.deleteLink(link)))
    } catch {
      message.error('原有输入连线删除失败，未创建新连线')
      cancelConnection()
      return
    }
    links.value = links.value.filter((link) => !replaced.includes(link))
    const link = {
      id: Math.max(0, ...links.value.map((item) => Number(item.id) || 0)) + 1,
      serverId: 0,
      from: output.id,
      fromIndex,
      to: input.id,
      toIndex
    }
    links.value.push(link)
    connecting.value = null
    markDirty()
    try {
      const savedLink = await graphWorkspace.saveLink(link, nodes.value)
      if (savedLink?.id != null) link.serverId = savedLink.id
    } catch {
      message.warning('连线已创建，但服务器保存失败')
    }
    return
  }
  const rect = viewport.value?.getBoundingClientRect()
  if (!rect) return
  const index = explicitIndex ?? connectionSlotAt(node, side, event)
  if (index < 0) {
    message.warning(`该节点没有可用的${side === 'left' ? '输入' : '输出'}端口`)
    return
  }
  nodeResize.value = null
  nodeDrag.value = null
  isPanning.value = false
  if (event.pointerId != null && viewport.value?.hasPointerCapture?.(event.pointerId)) viewport.value.releasePointerCapture(event.pointerId)
  connecting.value = {node, side, index}
  const source = portPoint(node, side === 'right' ? 'out' : 'in', index)
  connectionPointer.value = {
    x: source.x,
    y: source.y,
  }
  selectedId.value = node.id
}

async function addNode(item) {
  const id = `${item.type.toLowerCase()}-${Date.now()}`;
  const node = {
    id,
    type: item.type,
    title: item.title,
    icon: item.icon,
    color: item.color,
    x: 180 + (nodes.value.length % 3) * 280,
    y: 700 + Math.floor(nodes.value.length / 3) * 220,
    width: 220,
    height: 150,
    order: nodes.value.length + 1,
    mode: '启用',
    note: '',
    inputs: [{name: '输入', value: '待连接'}],
    outputs: [{name: '输出', type: '数据'}]
  };
  nodes.value.push(node);
  selectedId.value = id;
  markDirty();
  try {
    await graphWorkspace.saveNode(node)
  } catch {
    message.warning('节点已添加到画布，保存到服务器失败')
  }
  message.success(`已添加${item.title}节点`)
}

async function removeNode(id) {
  const node = nodes.value.find((item) => item.id === id);
  nodes.value = nodes.value.filter((node) => node.id !== id);
  links.value = links.value.filter((link) => link.from !== id && link.to !== id);
  if (selectedId.value === id) selectedId.value = null;
  markDirty();
  if (node) {
    try {
      await graphWorkspace.deleteLinksForNode(id);
      await graphWorkspace.deleteNode(node)
    } catch {
      message.warning('节点已移除，但服务器删除失败')
    }
  }
}

async function removeSelectedLink() {
  const link = selectedLink.value
  if (!link) return
  try {
    await graphWorkspace.deleteLink(link)
    links.value = links.value.filter((item) => item !== link)
    selectedLinkId.value = null
    markDirty()
    message.success('连线已删除')
  } catch {
    message.error('连线删除失败')
  }
}

async function addGroup() {
  const group = {id: `group-${Date.now()}`, title: '新建分组', x: 80, y: 680, width: 700, height: 230};
  groups.value.push(group);
  markDirty();
  try {
    await graphWorkspace.saveGroup(group)
  } catch {
    message.warning('分组已添加到画布，保存到服务器失败')
  }
}

function markDirty() {
  changeRevision.value += 1
  saved.value = false
}

function startAutoSave() {
  stopAutoSave()
  autoSaveTimer = window.setInterval(() => {
    if (!saved.value && !saving.value) saveCanvas({silent: true})
  }, autoSaveSeconds.value * 1000)
}

function stopAutoSave() {
  if (autoSaveTimer !== null) {
    window.clearInterval(autoSaveTimer)
    autoSaveTimer = null
  }
}

async function saveCanvas(options: { silent?: boolean } = {}) {
  if (saving.value) return false
  const revision = changeRevision.value
  saving.value = true
  try {
    if (!graphWorkspace.ready.value) {
      if (!options.silent) message.warning('画布尚未连接服务器，暂时无法保存')
      return false
    }
    await graphWorkspace.saveAll(nodes.value, links.value, groups.value)
    if (revision === changeRevision.value) {
      saved.value = true
      if (!options.silent) message.success('画布已保存')
    }
    return true
  } catch (error) {
    if (!options.silent) message.error(error instanceof Error ? error.message : '画布保存失败')
    return false
  } finally {
    saving.value = false
  }
}

function runWorkflow() {
  message.success('工作流已加入执行队列')
}
</script>

<style scoped>
.graph-shell {
  min-height: calc(100vh - 1px);
  background: #f5f6fa;
  color: #20283b;
}

.graph-header {
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 0 22px;
  background: #fff;
  border-bottom: 1px solid #e4e7ee;
}

.brand {
  color: #5c52ee;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .8px;
}

.divider {
  width: 1px;
  height: 26px;
  background: #e3e6ed;
}

.eyebrow {
  margin: 0 0 4px;
  color: #8c96a8;
  font-size: 10px;
  letter-spacing: .7px;
}

.graph-header h1 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 9px;
}

.autosave-control {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #8b95a8;
  font-size: 10px;
}

.autosave-control :deep(.n-select) {
  width: 68px;
}

.autosave-control :deep(.n-base-selection) {
  min-height: 28px;
}

.save-state {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-right: 5px;
  color: #8b95a8;
  font-size: 11px;
  transition: color .15s ease;
}

.save-state.saved {
  color: #2f9e6f;
}

.save-state.saving {
  color: #a27a28;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #e3a53c;
  display: inline-block;
}

.status-dot.saved, .status-dot.green {
  background: #49b788;
}

.status-dot.saving {
  background: #e3a53c;
}

.graph-body {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 270px;
  height: calc(100vh - 65px);
  min-height: 650px;
}

.node-library, .inspector {
  background: #fff;
}

.node-library {
  border-right: 1px solid #e1e5ed;
  padding: 18px 13px;
}

.library-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 13px;
  font-size: 13px;
  font-weight: 700;
}

.library-section {
  margin-top: 20px;
}

.section-label {
  display: block;
  margin: 0 7px 9px;
  color: #99a2b2;
  font-size: 10px;
}

.library-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 7px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #4e596c;
  text-align: left;
  cursor: pointer;
}

.library-item:hover {
  background: #f2f0ff;
}

.library-icon {
  width: 27px;
  height: 27px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  color: white;
  font-size: 13px;
}

.library-item strong, .library-item small {
  display: block;
}

.library-item strong {
  font-size: 11px;
  font-weight: 600;
}

.library-item small {
  margin-top: 2px;
  color: #9ca5b4;
  font-size: 9px;
}

.add-mark {
  margin-left: auto;
  color: #b5bdcb;
  font-size: 17px;
}

.library-footer {
  position: absolute;
  bottom: 18px;
  display: flex;
  align-items: center;
  gap: 7px;
  color: #9aa3b2;
  font-size: 10px;
}

.canvas-viewport {
  position: relative;
  overflow: hidden;
  background-color: #f7f8fb;
  background-image: radial-gradient(#d7dbe5 1px, transparent 1px);
  background-size: 22px 22px;
  cursor: grab;
  touch-action: none;
}

.canvas-viewport:active {
  cursor: grabbing;
}

.canvas-toolbar {
  position: absolute;
  z-index: 20;
  top: 14px;
  left: 16px;
  display: flex;
  gap: 8px;
}

.zoom-value {
  min-width: 54px;
}

.canvas-stage {
  position: absolute;
  width: 1600px;
  height: 980px;
  transform-origin: 0 0;
}

.link-layer {
  position: absolute;
  inset: 0;
  overflow: visible;
  pointer-events: none;
}

.canvas-link {
  stroke: #9a92e8;
  pointer-events: none;
  transition: stroke .12s ease, stroke-width .12s ease;
}

.canvas-link.selected {
  stroke: #6654ee;
  stroke-width: 3;
}

.link-hit-area {
  pointer-events: stroke;
  cursor: pointer;
}

.canvas-group {
  position: absolute;
  border: 1px solid #cfcaff;
  border-radius: 12px;
  background: rgba(239, 237, 255, .28);
  pointer-events: none;
}

.canvas-group span {
  position: absolute;
  top: -10px;
  left: 14px;
  padding: 2px 8px;
  border-radius: 5px;
  background: #e9e6ff;
  color: #6a60d7;
  font-size: 10px;
}

.graph-node {
  position: absolute;
  box-sizing: border-box;
  overflow: visible;
  min-width: 190px;
  border: 1px solid #d9dce5;
  border-radius: 9px;
  background: #fff;
  box-shadow: 0 5px 16px rgba(55, 62, 93, .1);
  user-select: none;
  cursor: move;
}

.graph-node.selected {
  border-color: #7268ef;
  box-shadow: 0 0 0 2px rgba(114, 104, 239, .22), 0 8px 22px rgba(65, 56, 140, .14);
}

.node-head-bg {
  box-sizing: border-box;
  height: calc(38px * var(--node-scale));
  margin: 1.5%;
  border-radius: 5px;
  color: #fff;
}

.node-head-content {
  display: flex;
  box-sizing: border-box;
  align-items: center;
  gap: calc(8px * var(--node-scale));
  height: 100%;
  margin: 0 1.5%;
}

.node-kind {
  font-size: calc(14px * var(--node-scale));
  opacity: .9;
}

.node-head-content strong {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  font-size: calc(11px * var(--node-scale));
  font-weight: 600;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-inline-input {
  min-width: 0;
  width: 100%;
  flex: 1 1 auto;
  border: 1px solid rgba(255, 255, 255, .65);
  border-radius: 3px;
  outline: none;
  background: rgba(255, 255, 255, .95);
  color: #394158;
  font-size: calc(10px * var(--node-scale));
  line-height: calc(18px * var(--node-scale));
  padding: 0 3px;
}

.node-body .node-inline-input {
  border-color: #c7c3f5;
  background: #fff;
  color: #4d5870;
}

.node-value-input {
  max-width: 120px;
  margin-left: auto;
}

.node-resizer {
  position: absolute;
  z-index: 3;
  display: block;
}

.node-resizer-left, .node-resizer-right {
  top: 8px;
  bottom: 8px;
  width: 9px;
  cursor: ew-resize;
}

.node-resizer-left {
  left: -4px;
}

.node-resizer-right {
  right: -4px;
}

.node-resizer-left:hover, .node-resizer-right:hover {
  background: rgba(102, 84, 238, .12);
  cursor: ew-resize;
}

.node-resizer-left.connect-source, .node-resizer-right.connect-source {
  background: rgba(102, 84, 238, .18);
  cursor: crosshair;
}

.node-resizer-left.connect-target, .node-resizer-right.connect-target {
  background: rgba(73, 183, 136, .18);
  cursor: crosshair;
}

.node-resizer-top, .node-resizer-bottom {
  left: 8px;
  right: 8px;
  height: 9px;
  cursor: ns-resize;
}

.node-resizer-top {
  top: -4px;
}

.node-resizer-bottom {
  bottom: -4px;
}

.node-menu {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: rgba(255, 255, 255, .75);
  font-size: 17px;
  cursor: pointer;
}

.node-body {
  margin: 0 3% 2%;
  padding: 0;
}

.port-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: calc(7px * var(--node-scale));
  min-height: calc(27px * var(--node-scale));
  color: #667186;
  font-size: calc(11px * var(--node-scale));
  line-height: 1.35;
}

.port-row > span:not(.port) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.port-row small {
  min-width: 0;
  margin-left: auto;
  max-width: calc(120px * var(--node-scale));
  overflow: hidden;
  color: #a0a8b6;
  font-size: calc(10px * var(--node-scale));
  text-overflow: ellipsis;
  white-space: nowrap;
}

.output-row {
  justify-content: flex-end;
}

.output-row small {
  margin: 0 auto 0 0;
}

.port {
  position: relative;
  z-index: 5;
  width: calc(12px * var(--node-scale));
  height: calc(12px * var(--node-scale));
  flex: 0 0 auto;
  border: calc(2px * var(--node-scale)) solid #fff;
  border-radius: 50%;
  box-shadow: 0 0 0 1px #9a92e8;
  background: #b4aff5;
  cursor: crosshair;
  transition: transform .12s ease, background .12s ease, box-shadow .12s ease;
}

.port:hover, .port:active {
  transform: scale(1.25);
  background: #6654ee;
  box-shadow: 0 0 0 2px rgba(102, 84, 238, .22);
}

.port.connect-source {
  transform: scale(1.25);
  background: #6654ee;
  box-shadow: 0 0 0 3px rgba(102, 84, 238, .2);
}

.port.connect-target {
  transform: scale(1.18);
  background: #43b589;
  box-shadow: 0 0 0 3px rgba(67, 181, 137, .2);
}

.port.connect-disabled {
  opacity: .35;
}

.input-port {
  margin-left: calc(-3% - (4.5px * var(--node-scale)));
}

.output-port {
  margin-right: calc(-3% - (4.5px * var(--node-scale)));
}

.canvas-help {
  position: absolute;
  right: 16px;
  bottom: 13px;
  display: flex;
  gap: 14px;
  color: #9ba4b3;
  font-size: 10px;
}

.connection-state {
  color: #6654ee;
  font-size: 10px;
  font-weight: 600;
}

.connection-preview {
  pointer-events: none;
}

.inspector {
  border-left: 1px solid #e1e5ed;
}

.inspector-head {
  display: flex;
  justify-content: space-between;
  padding: 19px 16px 15px;
  border-bottom: 1px solid #e8eaf0;
}

.inspector-head h2 {
  margin: 3px 0 0;
  font-size: 14px;
  font-weight: 600;
}

.inspector-content {
  padding: 18px 16px;
}

.inspector-meta {
  margin-top: 20px;
  padding-top: 14px;
  border-top: 1px solid #eceef3;
}

.inspector-meta div {
  display: flex;
  justify-content: space-between;
  margin-top: 9px;
  color: #97a0b0;
  font-size: 10px;
}

.inspector-meta strong {
  color: #556074;
  font-size: 10px;
  font-weight: 600;
}

.empty-inspector {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 70%;
  padding: 25px;
  text-align: center;
  color: #9aa3b2;
}

.empty-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  margin-bottom: 13px;
  border-radius: 11px;
  background: #f1efff;
  color: #6d63e6;
  font-size: 19px;
}

.empty-inspector strong {
  color: #5b6578;
  font-size: 12px;
}

.empty-inspector p {
  margin: 8px 0 0;
  font-size: 10px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .graph-body {
    grid-template-columns: 190px minmax(0, 1fr);
  }

  .inspector {
    display: none;
  }
}

@media (max-width: 640px) {
  .graph-header {
    padding: 0 12px;
  }

  .brand, .save-state, .autosave-control, .header-actions .n-button:first-of-type {
    display: none;
  }

  .graph-body {
    grid-template-columns: 1fr;
  }

  .node-library {
    display: none;
  }

  .canvas-help {
    display: none;
  }
}
</style>
