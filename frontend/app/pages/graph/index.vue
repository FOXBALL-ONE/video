<template>
  <main class="graph-shell">
    <header class="graph-header">
      <div class="flex min-w-0 items-center gap-3">
        <NuxtLink :to="`/projects/${activeProject.id}`" class="back-link">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"
               stroke-width="2">
            <path d="m12 19-7-7 7-7"/>
            <path d="M19 12H5"/>
          </svg>
          <span>返回</span>
        </NuxtLink>
        <span aria-hidden="true" class="divider"></span>
        <div class="min-w-0">
          <p class="eyebrow">GRAPH CANVAS · {{ activeProject.code }}</p>
          <h1 class="truncate">{{ activeProject.name }}<span v-if="activeEpisode" class="font-normal"> ·
            {{ activeEpisode.title }}</span> 工作流</h1>
        </div>
      </div>
      <div class="header-actions">
        <div class="autosave-control">
          <span>自动保存</span>
          <n-select v-model:value="autoSaveSeconds" :options="autoSaveOptions" aria-label="自动保存间隔" size="small"/>
        </div>
        <span :class="{ saved, saving }" class="save-state"><span :class="{ saved, saving }"
                                                                  class="status-dot"></span>{{
            saving ? '保存中...' : saved ? '已保存' : '未保存更改'
          }}</span>
        <n-button secondary size="small" @click="autoLayout">
          <span class="btn-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"
                 stroke-width="2">
              <path d="M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z"/>
              <path d="M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z"/>
              <path d="M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Z"/>
              <path d="M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Z"/>
            </svg>
          </span>
          自动布局
        </n-button>
        <n-button secondary size="small" @click="saveCanvas()">保存</n-button>
        <n-button color="#6654ee" size="small" type="primary" @click="runWorkflow">全部运行</n-button>
      </div>
    </header>

    <div class="graph-body">
      <aside class="node-palette">
        <div class="palette-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"
               stroke-width="2">
            <path d="M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0"/>
            <path d="m21 21-4.3-4.3"/>
          </svg>
          <n-input v-model:value="nodeSearch" clearable placeholder="搜索节点..." size="small"/>
        </div>
        <div class="palette-list">
          <section v-for="category in paletteCategories" :key="category.key" class="palette-category">
            <button class="category-toggle" type="button" @click="toggleCategory(category.key)">
              <svg :class="{ collapsed: collapsedCategories.has(category.key) }" viewBox="0 0 24 24" fill="none"
                   stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="2">
                <path d="m6 9 6 6 6-6"/>
              </svg>
              <span>{{ category.label }}</span>
              <small>{{ category.nodes.length }}</small>
            </button>
            <template v-if="!collapsedCategories.has(category.key)">
              <div v-for="item in category.nodes" :key="item.type"
                   :class="{ disabled: isSingletonAdded(item), dragging: draggingType === item.type }"
                   :draggable="!isSingletonAdded(item)"
                   :title="item.title"
                   class="palette-item"
                   @dragend="draggingType = ''"
                   @dragstart="onPaletteDragStart($event, item)"
                   @click="addNode(item)">
                <span :style="{ background: item.color }" class="item-icon">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round"
                       stroke-linejoin="round" stroke-width="2">
                    <path v-for="(path, pathIndex) in item.icon" :key="pathIndex" :d="path"/>
                  </svg>
                </span>
                <span class="item-label"><strong>{{ item.title }}</strong></span>
                <span v-if="isSingletonAdded(item)" class="added-mark">已添加</span>
                <span v-else class="add-mark">+</span>
              </div>
            </template>
          </section>
          <p v-if="!paletteCategories.length" class="palette-empty">没有匹配「{{ nodeSearch }}」的节点</p>
        </div>
        <div class="palette-footer"><span class="status-dot green"></span>画布同步正常</div>
      </aside>

      <section ref="viewport" class="canvas-viewport"
               @dragover.prevent
               @drop.prevent="onDropFromPalette"
               @dblclick="cancelConnection"
               @pointercancel="handleViewportPointerUp"
               @pointerdown="startPan"
               @pointerleave="endPan"
               @pointermove="pan"
               @pointerup="handleViewportPointerUp"
               @wheel.prevent="zoomCanvas">
        <div v-if="connecting || selectedLink" class="canvas-toastbar">
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
            <path v-for="link in links" :key="link.id" :class="{ selected: selectedLinkId === link.id }"
                  :d="linkPath(link)"
                  :style="{ stroke: linkColor(link) }"
                  class="canvas-link" fill="none" marker-end="url(#arrow)"
                  stroke-width="2"/>
            <path v-if="connectionPath" :d="connectionPath" :style="{ stroke: connectionColor }"
                  class="connection-preview" fill="none"
                  stroke-dasharray="6 5" stroke-width="2"/>
          </svg>
          <div v-for="group in groups" :key="group.id" :style="groupStyle(group)" class="canvas-group">
            <span>{{ group.title }}</span></div>
          <article v-for="node in nodes" :key="node.id" :class="{ selected: selectedId === node.id }"
                   :style="nodeStyle(node)"
                   class="graph-node" @wheel.stop.prevent="resizeNodeByWheel($event, node)"
                   @pointerdown.stop="startNodeDrag($event, node)" @click.stop="selectedId = node.id">
            <div class="node-head">
              <span :style="{ background: nodeIconBackground(node), color: nodeColor(node) }" class="node-icon">
                <svg v-if="nodeDefIcon(node)" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-linecap="round" stroke-linejoin="round" stroke-width="2">
                  <path v-for="(path, pathIndex) in nodeDefIcon(node)" :key="pathIndex" :d="path"/>
                </svg>
                <template v-else>{{ node.icon || '◇' }}</template>
              </span>
              <strong v-if="!isEditing(node, 'title')" class="node-title"
                      @dblclick.stop="beginEdit(node, 'title', undefined, node.title, $event)">{{
                  node.title
                }}</strong>
              <input v-else ref="editInput" v-model="editText" class="node-inline-input" @blur="finishEdit"
                     @pointerdown.stop @mousedown.stop @click.stop
                     @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/>
              <button aria-label="运行节点" class="node-run" title="运行此节点" type="button" @pointerdown.stop
                      @mousedown.stop @click.stop.prevent="runNode(node)">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-linecap="round"
                     stroke-linejoin="round" stroke-width="2">
                  <path d="M6 3l14 9-14 9z"/>
                </svg>
              </button>
              <button aria-label="移除节点" class="node-menu" type="button" @pointerdown.stop @mousedown.stop
                      @click.stop.prevent="removeNode(node.id)">×
              </button>
            </div>
            <div class="node-body">
              <div v-for="(input, inputIndex) in displayInputs(node)" :key="`in-${inputIndex}`" class="port-row">
                <span :class="portStateClass(node, 'left', inputIndex)"
                      :style="{ borderColor: input.color }"
                      class="port input-port" title="拖拽到此处连接"
                      @pointerdown.stop.prevent="startPortConnection($event, node, 'left', inputIndex)"
                      @pointerup.stop.prevent="completePortConnection($event, node, 'left', inputIndex)"></span>
                <span v-if="!isEditing(node, 'input-name', inputIndex)" class="port-label"
                      @dblclick.stop="canEditPorts(node) && beginEdit(node, 'input-name', inputIndex, input.name ?? input.label, $event)">{{
                    input.label
                  }}</span>
                <input v-else ref="editInput" v-model="editText" class="node-inline-input" @blur="finishEdit"
                       @pointerdown.stop @mousedown.stop @click.stop @keydown.enter.prevent="finishEdit"
                       @keydown.esc.prevent="cancelEdit"/>
                <span v-if="input.required" class="required-mark">*</span>
                <small v-if="input.value != null && !isEditing(node, 'input-value', inputIndex)"
                       @dblclick.stop="beginEdit(node, 'input-value', inputIndex, input.value, $event)">{{
                    input.value
                  }}</small>
                <input v-else-if="isEditing(node, 'input-value', inputIndex)" ref="editInput" v-model="editText"
                       class="node-inline-input node-value-input" @blur="finishEdit" @pointerdown.stop
                       @mousedown.stop @click.stop @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/>
              </div>
              <div class="port-row status-row">
                <span aria-hidden="true" class="status-icon"></span>
                <span>空闲</span>
              </div>
              <div v-for="(output, outputIndex) in displayOutputs(node)" :key="`out-${outputIndex}`"
                   class="port-row output-row">
                <small v-if="output.type && !isEditing(node, 'output-type', outputIndex)"
                       @dblclick.stop="beginEdit(node, 'output-type', outputIndex, output.type, $event)">{{
                    output.type
                  }}</small>
                <input v-else-if="isEditing(node, 'output-type', outputIndex)" ref="editInput" v-model="editText"
                       class="node-inline-input node-value-input" @blur="finishEdit" @pointerdown.stop
                       @mousedown.stop @click.stop @keydown.enter.prevent="finishEdit" @keydown.esc.prevent="cancelEdit"/>
                <span v-if="!isEditing(node, 'output-name', outputIndex)" class="port-label"
                      @dblclick.stop="canEditPorts(node) && beginEdit(node, 'output-name', outputIndex, output.name ?? output.label, $event)">{{
                    output.label
                  }}</span>
                <input v-else ref="editInput" v-model="editText" class="node-inline-input" @blur="finishEdit"
                       @pointerdown.stop @mousedown.stop @click.stop @keydown.enter.prevent="finishEdit"
                       @keydown.esc.prevent="cancelEdit"/>
                <span :class="portStateClass(node, 'right', outputIndex)"
                      :style="{ borderColor: output.color }"
                      class="port output-port" title="拖拽到输入端口连接"
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
                  class="node-resizer node-resizer-right"
                  @pointerdown.stop="handleEdgePointerDown($event, node, 'right')"
                  @dblclick.stop.prevent="beginEdgeConnection($event, node, 'right')"></span>
            <span aria-hidden="true" class="node-resizer node-resizer-top"
                  @pointerdown.stop="startNodeResize($event, node, 'top')"></span>
            <span aria-hidden="true" class="node-resizer node-resizer-bottom"
                  @pointerdown.stop="startNodeResize($event, node, 'bottom')"></span>
          </article>
        </div>

        <div class="canvas-controls">
          <button aria-label="放大" title="放大" type="button" @click="changeZoom(0.1)">
            <svg viewBox="0 0 32 32">
              <path d="M32 18.133H18.133V32h-4.266V18.133H0v-4.266h13.867V0h4.266v13.867H32z"/>
            </svg>
          </button>
          <button aria-label="缩小" title="缩小" type="button" @click="changeZoom(-0.1)">
            <svg viewBox="0 0 32 5">
              <path d="M0 0h32v4.2H0z"/>
            </svg>
          </button>
          <button aria-label="适应画布" title="适应画布" type="button" @click="fitView">
            <svg viewBox="0 0 32 30">
              <path d="M3.692 4.63c0-.53.4-.938.939-.938h5.215V0H4.708C2.13 0 0 2.054 0 4.63v5.216h3.692V4.631zM27.354 0h-5.2v3.692h5.17c.53 0 .984.4.984.939v5.215H32V4.631A4.624 4.624 0 0027.354 0zm.954 24.83c0 .532-.4.94-.939.94h-5.215v3.768h5.215c2.577 0 4.631-2.13 4.631-4.707v-5.139h-3.692v5.139zm-23.677.94c-.531 0-.939-.4-.939-.94v-5.138H0v5.139c0 2.577 2.13 4.707 4.708 4.707h5.138V25.77H4.631z"/>
            </svg>
          </button>
          <button aria-label="新建分组" title="新建分组" type="button" @click="addGroup">＋</button>
        </div>

        <div class="canvas-minimap" @pointerdown.stop>
          <svg :viewBox="minimapViewBox" height="120" width="180">
            <title>Mini Map</title>
            <rect v-for="node in nodes" :key="`map-${node.id}`"
                  :class="{ active: selectedId === node.id }"
                  :height="minimapRect(node).h" :width="minimapRect(node).w"
                  :x="minimapRect(node).x" :y="minimapRect(node).y" class="minimap-node" rx="3"/>
            <rect :height="minimapViewRect.h" :width="minimapViewRect.w" :x="minimapViewRect.x"
                  :y="minimapViewRect.y" class="minimap-view"/>
          </svg>
        </div>
      </section>

      <aside class="inspector">
        <div class="inspector-head">
          <div><span class="eyebrow">INSPECTOR</span>
            <h2>{{ selectedNode ? selectedNode.title : '画布属性' }}</h2></div>
          <n-button aria-label="关闭属性面板" text @click="selectedId = null">×</n-button>
        </div>
        <div class="inspector-scroll">
          <template v-if="selectedNode">
            <div class="inspector-content">
              <n-form label-placement="top" size="small">
                <n-form-item label="节点标题">
                  <n-input v-model:value="selectedNode.title" @update:value="markDirty"/>
                </n-form-item>
                <n-form-item label="节点类型">
                  <n-input :value="nodeTypeLabel(selectedNode)" readonly/>
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
                <div v-if="nodeDef(selectedNode)"><span>输入 / 输出</span>
                  <strong>{{ nodeDef(selectedNode)!.inputs.length }} / {{ nodeDef(selectedNode)!.outputs.length }}</strong>
                </div>
              </div>
            </div>
          </template>
          <div v-else class="empty-inspector"><span class="empty-icon">⌘</span><strong>选择一个节点</strong>
            <p>点击画布中的节点查看属性和输入输出。</p></div>
          <div class="inspector-stats">
            <h4>画布统计</h4>
            <div class="stat-row"><span class="stat-dot all"></span><span>总节点</span><strong>{{ nodes.length }}</strong>
            </div>
            <div class="stat-row"><span class="stat-dot done"></span><span>已完成</span><strong>0</strong></div>
            <div class="stat-row"><span class="stat-dot running"></span><span>运行中</span><strong>0</strong></div>
            <div class="stat-row"><span class="stat-dot pending"></span><span>待处理</span><strong>{{ nodes.length }}</strong>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <footer class="canvas-statusbar">
      <span>节点: {{ nodes.length }}</span>
      <span>连线: {{ links.length }}</span>
      <span>分组: {{ groups.length }}</span>
      <div class="flex-1"></div>
      <span>缩放 {{ Math.round(zoom * 100) }}%</span>
      <span :class="{ saved }" class="statusbar-save">{{ saving ? '保存中...' : saved ? '已保存' : '未保存更改' }}</span>
    </footer>
  </main>
</template>

<script lang="ts" setup>
import {computed, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {resolveNodeDef} from '~/types/graph'
import type {CatalogNodeType} from '~/types/graph'

const message = useMessage()
const {activeProject} = useProjectWorkspace()
const {activeEpisodeId, activeEpisode} = useEpisodeWorkspace()
const route = useRoute()
const graphWorkspace = useGraphWorkspace()
const episodeId = computed(() => String(route.params.episodeId || activeEpisodeId.value || ''))
const nodeSearch = ref('')
const viewport = ref(null)
const selectedId = ref('script-demo')
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
const stageSize = {width: 2200, height: 1400}
const collapsedCategories = ref(new Set<string>())
const draggingType = ref('')

const catalog = computed(() => graphWorkspace.catalog.value)

const nodes = ref([
  demoNode('script_input', 'script-demo', 120, 330),
  demoNode('character_extract', 'character-demo', 520, 120),
  demoNode('prop_extract', 'prop-demo', 520, 310),
  demoNode('scene_extract', 'scene-demo', 520, 500),
  demoNode('storyboard_plan', 'storyboard-demo', 900, 230),
  demoNode('seedance2_prompt', 's2prompt-demo', 1260, 90),
  demoNode('seedance2_video', 's2video-demo', 1260, 430),
  demoNode('compose', 'compose-demo', 1620, 240),
])
const links = ref([
  {id: 1, from: 'script-demo', fromIndex: 0, to: 'character-demo', toIndex: 0},
  {id: 2, from: 'script-demo', fromIndex: 0, to: 'scene-demo', toIndex: 0},
  {id: 3, from: 'script-demo', fromIndex: 0, to: 'prop-demo', toIndex: 0},
  {id: 4, from: 'script-demo', fromIndex: 0, to: 'storyboard-demo', toIndex: 0},
  {id: 5, from: 'character-demo', fromIndex: 0, to: 'storyboard-demo', toIndex: 1},
  {id: 6, from: 'scene-demo', fromIndex: 0, to: 'storyboard-demo', toIndex: 2},
  {id: 7, from: 'storyboard-demo', fromIndex: 0, to: 's2prompt-demo', toIndex: 0},
  {id: 8, from: 's2prompt-demo', fromIndex: 0, to: 's2video-demo', toIndex: 0},
  {id: 9, from: 's2video-demo', fromIndex: 0, to: 'compose-demo', toIndex: 1},
])
const groups = ref([{id: 'group-1', title: '示例 · 剧本到成片', x: 70, y: 60, width: 1100, height: 640}])

function demoNode(type: string, id: string, x: number, y: number) {
  const def = resolveNodeDef(catalog.value, type)
  return {
    id,
    type,
    title: def?.title || type,
    icon: def ? '' : '◇',
    color: def?.color || '#6654ee',
    x,
    y,
    width: 220,
    height: 150,
    order: 0,
    mode: '启用',
    note: '',
    inputs: (def?.inputs || []).map((port) => ({handle: port.handle, name: port.label, color: port.color, required: port.required})),
    outputs: (def?.outputs || []).map((port) => ({handle: port.handle, name: port.label, color: port.color})),
  }
}

const paletteCategories = computed(() => {
  const search = nodeSearch.value.trim().toLowerCase()
  return catalog.value
      .map((category) => ({
        ...category,
        nodes: category.nodes.filter((item) => !search || `${item.title}${item.type}`.toLowerCase().includes(search)),
      }))
      .filter((category) => category.nodes.length)
})

function toggleCategory(key: string) {
  const next = new Set(collapsedCategories.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedCategories.value = next
}

function isSingletonAdded(item: CatalogNodeType) {
  return Boolean(item.singleton) && nodes.value.some((node) => node.type === item.type)
}

function onPaletteDragStart(event: DragEvent, item: CatalogNodeType) {
  draggingType.value = item.type
  event.dataTransfer?.setData('text/plain', item.type)
  event.dataTransfer?.setData('application/x-graph-node', item.type)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}

async function onDropFromPalette(event: DragEvent) {
  draggingType.value = ''
  const type = event.dataTransfer?.getData('application/x-graph-node') || event.dataTransfer?.getData('text/plain')
  if (!type) return
  const def = resolveNodeDef(catalog.value, type)
  if (!def) return
  if (isSingletonAdded(def)) {
    message.warning(`「${def.title}」在画布中只能添加一个`)
    return
  }
  const rect = viewport.value?.getBoundingClientRect()
  if (!rect) return
  const x = Math.max(0, (event.clientX - rect.left - panOffset.value.x) / zoom.value - 110)
  const y = Math.max(0, (event.clientY - rect.top - panOffset.value.y) / zoom.value - 20)
  await addNode(def, x, y)
}

const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedId.value))
const selectedLink = computed(() => links.value.find((link) => link.id === selectedLinkId.value))
const modeOptions = [{label: '启用', value: '启用'}, {label: '旁路', value: '旁路'}, {label: '禁用', value: '禁用'}]
const stageStyle = computed(() => ({transform: `translate(${panOffset.value.x}px, ${panOffset.value.y}px) scale(${zoom.value})`}))

function nodeDef(node: any) {
  return resolveNodeDef(catalog.value, node.type)
}

function nodeDefIcon(node: any) {
  return nodeDef(node)?.icon || null
}

function nodeColor(node: any) {
  return nodeDef(node)?.color || node.color || '#6654ee'
}

function nodeIconBackground(node: any) {
  return `${nodeColor(node)}1f`
}

function nodeTypeLabel(node: any) {
  return nodeDef(node)?.type || node.type
}

/** 目录节点的端口由类型定义固定，只有旧版自由节点才允许内联编辑端口名。 */
function canEditPorts(node: any) {
  return !nodeDef(node)
}

/** 目录节点的端口展示直接来自目录定义；旧版 ComfyUI 节点回退到存储的插槽列表。 */
function displayInputs(node: any) {
  const def = nodeDef(node)
  if (def) return def.inputs.map((port) => ({label: port.label, color: port.color, required: Boolean(port.required)}))
  return (node.inputs || []).map((input: any) => ({
    label: input.name ?? input.label ?? '输入',
    value: input.value ?? input.widget,
    color: input.color || '#b4aff5',
  }))
}

function displayOutputs(node: any) {
  const def = nodeDef(node)
  if (def) return def.outputs.map((port) => ({label: port.label, color: port.color}))
  return (node.outputs || []).map((output: any) => ({
    label: output.name ?? output.label ?? '输出',
    type: output.type ?? output.dtype,
    color: output.color || '#b4aff5',
  }))
}

const connectionPath = computed(() => {
  if (!connecting.value) return ''
  const source = portPoint(connecting.value.node, connecting.value.side === 'right' ? 'out' : 'in', connecting.value.index)
  const target = connectionPointer.value
  const bend = Math.max(50, Math.abs(target.x - source.x) * 0.45)
  return connecting.value.side === 'right'
      ? `M ${source.x} ${source.y} C ${source.x + bend} ${source.y}, ${target.x - bend} ${target.y}, ${target.x} ${target.y}`
      : `M ${target.x} ${target.y} C ${target.x + bend} ${target.y}, ${source.x - bend} ${source.y}, ${source.x} ${source.y}`
})

const connectionColor = computed(() => {
  if (!connecting.value) return '#6654ee'
  const def = nodeDef(connecting.value.node)
  const port = connecting.value.side === 'right' ? def?.outputs[connecting.value.index] : null
  return port?.color || '#6654ee'
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
  graphWorkspace.loadCatalog()
  const result = await graphWorkspace.load(activeProject.value?.id, episodeId.value)
  if (!result) return
  const loadedNodes = (result.nodes || []).map((node) => ({
    id: node.nodeKey ?? node.node_key,
    type: node.nodeType ?? node.node_type,
    title: node.title || node.nodeType || node.node_type,
    icon: '',
    color: node.color || '#6654ee',
    x: node.positionX ?? node.position_x ?? 0,
    y: node.positionY ?? node.position_y ?? 0,
    width: node.width || 220,
    height: node.height || 150,
    order: node.executionOrder ?? node.execution_order ?? 0,
    mode: (node.mode ?? 0) === 2 ? '旁路' : (node.mode ?? 0) === 4 ? '禁用' : '启用',
    note: '',
    inputs: Array.isArray(node.inputs) ? node.inputs : [],
    outputs: Array.isArray(node.outputs) ? node.outputs : [],
  }))
  if (loadedNodes.length) {
    nodes.value = loadedNodes
    fitView()
  }
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
  const inputs = displayInputs(node)
  const outputs = displayOutputs(node)
  const longest = Math.max(node.title?.length || 0, ...inputs.map((item) => `${item.label}${item.value ?? ''}`.length), ...outputs.map((item) => `${item.label}${item.type ?? ''}`.length))
  return Math.max(190, Math.min(360, 120 + longest * 5.2))
}

function nodeBaseMinHeight(node) {
  const rowCount = displayInputs(node).length + displayOutputs(node).length + 1
  return Math.max(110, 58 + rowCount * 27)
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
  const inputCount = displayInputs(node).length
  const rowIndex = kind === 'out' ? inputCount + 1 + index : index
  const inset = nodeRenderWidth(node) * 0.015
  const scale = nodeContentScale(node)
  const y = inset + 38 * scale + inset + 13.5 * scale + rowIndex * 27 * scale
  return {x: kind === 'out' ? node.x + nodeRenderWidth(node) : node.x, y: node.y + y}
}

function connectionSlotAt(node, side, event) {
  const count = side === 'right' ? displayOutputs(node).length : displayInputs(node).length
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
  return side === 'left' ? displayInputs(node).length > 0 : displayOutputs(node).length > 0
}

function portStateClass(node, side, index) {
  if (!connecting.value) return ''
  if (connecting.value.node === node && connecting.value.side === side && connecting.value.index === index) return 'connect-source'
  if (connecting.value.node !== node && connecting.value.side !== side) return 'connect-target'
  return 'connect-disabled'
}

function linkColor(link) {
  if (selectedLinkId.value === link.id) return '#6654ee'
  const from = nodes.value.find((node) => node.id === link.from)
  if (!from) return '#9a92e8'
  return displayOutputs(from)[link.fromIndex]?.color || '#9a92e8'
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
  if (!nodes.value.length) {
    zoom.value = 0.8
    panOffset.value = {x: 40, y: 40}
    return
  }
  const minX = Math.min(...nodes.value.map((node) => node.x))
  const minY = Math.min(...nodes.value.map((node) => node.y))
  const maxX = Math.max(...nodes.value.map((node) => node.x + nodeRenderWidth(node)))
  const maxY = Math.max(...nodes.value.map((node) => node.y + nodeRenderHeight(node)))
  const width = viewport.value?.clientWidth || 1200
  const height = viewport.value?.clientHeight || 800
  const scale = Math.min(1.2, Math.max(0.3, Math.min(width / (maxX - minX + 160), height / (maxY - minY + 160))))
  zoom.value = +scale.toFixed(2)
  panOffset.value = {x: 80 - minX * scale, y: 80 - minY * scale}
}

function startPan(event) {
  if (event.target?.closest?.('.graph-node, .canvas-controls, .canvas-minimap, button, .link-hit-area')) return;
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

async function addNode(item: CatalogNodeType, x?: number, y?: number) {
  if (item.singleton && nodes.value.some((node) => node.type === item.type)) {
    message.warning(`「${item.title}」在画布中只能添加一个`)
    return
  }
  const id = `${item.type}-${Date.now()}`;
  const node = {
    id,
    type: item.type,
    title: item.title,
    icon: '',
    color: item.color,
    x: x ?? 180 + (nodes.value.length % 3) * 280,
    y: y ?? 700 + Math.floor(nodes.value.length / 3) * 220,
    width: 220,
    height: 150,
    order: nodes.value.length + 1,
    mode: '启用',
    note: '',
    inputs: item.inputs.map((port) => ({handle: port.handle, name: port.label, color: port.color, required: port.required})),
    outputs: item.outputs.map((port) => ({handle: port.handle, name: port.label, color: port.color}))
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

function runNode(node) {
  message.success(`「${node.title}」已加入执行队列`)
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

/** 按连线关系做拓扑分层布局：无入边的节点放第一列，其余按最长路径深度排列。 */
async function autoLayout() {
  if (!nodes.value.length) return
  const depth = new Map()
  const inDegree = new Map()
  nodes.value.forEach((node) => {
    depth.set(node.id, 0)
    inDegree.set(node.id, 0)
  })
  links.value.forEach((link) => {
    if (inDegree.has(link.to)) inDegree.set(link.to, (inDegree.get(link.to) || 0) + 1)
  })
  const queue = nodes.value.filter((node) => (inDegree.get(node.id) || 0) === 0).map((node) => node.id)
  const visited = new Set(queue)
  while (queue.length) {
    const id = queue.shift()
    links.value.filter((link) => link.from === id).forEach((link) => {
      if (!depth.has(link.to)) return
      depth.set(link.to, Math.max(depth.get(link.to) || 0, (depth.get(id) || 0) + 1))
      const next = (inDegree.get(link.to) || 0) - 1
      inDegree.set(link.to, next)
      if (next === 0 && !visited.has(link.to)) {
        visited.add(link.to)
        queue.push(link.to)
      }
    })
  }
  // 成环等无法入队的节点直接跟在已布局节点后面，避免丢失。
  nodes.value.forEach((node) => {
    if (!visited.has(node.id)) {
      depth.set(node.id, nodes.value.length)
      visited.add(node.id)
    }
  })
  const columns = new Map()
  nodes.value.forEach((node) => {
    const column = depth.get(node.id) || 0
    if (!columns.has(column)) columns.set(column, [])
    columns.get(column).push(node)
  })
  let x = 60
  for (const column of [...columns.keys()].sort((a, b) => a - b)) {
    const columnNodes = columns.get(column).sort((a, b) => a.y - b.y)
    let y = 60
    let columnWidth = 0
    columnNodes.forEach((node) => {
      node.x = Math.round(x)
      node.y = Math.round(y)
      y += nodeRenderHeight(node) + 40
      columnWidth = Math.max(columnWidth, nodeRenderWidth(node))
    })
    x += columnWidth + 100
  }
  markDirty()
  const ok = await saveCanvas({silent: true})
  if (ok) message.success('已完成自动布局并保存')
  else message.warning('已自动布局，但保存到服务器失败')
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

const minimapBounds = computed(() => {
  if (!nodes.value.length) return {minX: 0, minY: 0, maxX: 1600, maxY: 980}
  const minX = Math.min(...nodes.value.map((node) => node.x)) - 80
  const minY = Math.min(...nodes.value.map((node) => node.y)) - 80
  const maxX = Math.max(...nodes.value.map((node) => node.x + nodeRenderWidth(node))) + 80
  const maxY = Math.max(...nodes.value.map((node) => node.y + nodeRenderHeight(node))) + 80
  return {minX, minY, maxX: Math.max(maxX, minX + 400), maxY: Math.max(maxY, minY + 300)}
})
const minimapViewBox = computed(() => {
  const bounds = minimapBounds.value
  return `${bounds.minX} ${bounds.minY} ${bounds.maxX - bounds.minX} ${bounds.maxY - bounds.minY}`
})

function minimapRect(node) {
  return {
    x: node.x,
    y: node.y,
    w: nodeRenderWidth(node),
    h: nodeRenderHeight(node),
  }
}

const minimapViewRect = computed(() => ({
  x: -panOffset.value.x / zoom.value,
  y: -panOffset.value.y / zoom.value,
  w: (viewport.value?.clientWidth || 1200) / zoom.value,
  h: (viewport.value?.clientHeight || 800) / zoom.value,
}))
</script>

<style scoped>
.graph-shell {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 65px);
  min-height: 640px;
  background: #f5f6fa;
  color: #20283b;
}

.graph-header {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 0 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ee;
}

.back-link {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  border-radius: 7px;
  color: #6b7688;
  font-size: 12px;
  transition: background .15s ease, color .15s ease;
}

.back-link:hover {
  background: #f0eeff;
  color: #5c52ee;
}

.back-link svg {
  width: 13px;
  height: 13px;
}

.divider {
  width: 1px;
  height: 26px;
  background: #e3e6ed;
}

.eyebrow {
  margin: 0 0 3px;
  color: #8c96a8;
  font-size: 10px;
  letter-spacing: .7px;
}

.graph-header h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 9px;
}

.btn-icon {
  display: inline-flex;
  margin-right: 4px;
}

.btn-icon svg {
  width: 13px;
  height: 13px;
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
  display: flex;
  flex: 1;
  min-height: 0;
}

.node-palette {
  display: flex;
  flex-direction: column;
  width: 200px;
  flex: 0 0 200px;
  background: #fff;
  border-right: 1px solid #e1e5ed;
}

.palette-search {
  position: relative;
  display: flex;
  align-items: center;
  padding: 10px 10px;
  border-bottom: 1px solid #edeef3;
}

.palette-search > svg {
  position: absolute;
  left: 17px;
  width: 13px;
  height: 13px;
  color: #9aa3b2;
  pointer-events: none;
}

.palette-search :deep(.n-input__input-el) {
  padding-left: 16px;
}

.palette-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px;
}

.palette-category {
  margin-bottom: 6px;
}

.category-toggle {
  display: flex;
  align-items: center;
  gap: 5px;
  width: 100%;
  padding: 5px 6px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #8b95a8;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
}

.category-toggle:hover {
  color: #3f4a5e;
  background: #f5f4ff;
}

.category-toggle svg {
  width: 11px;
  height: 11px;
  transition: transform .15s ease;
}

.category-toggle svg.collapsed {
  transform: rotate(-90deg);
}

.category-toggle small {
  margin-left: auto;
  color: #b3bac6;
  font-size: 9px;
  font-weight: 400;
}

.palette-item {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 1px 0 1px 6px;
  padding: 6px 7px;
  border-radius: 7px;
  cursor: grab;
  transition: background .12s ease;
}

.palette-item:hover {
  background: #f2f0ff;
}

.palette-item.dragging {
  opacity: .55;
}

.palette-item.disabled {
  cursor: not-allowed;
  opacity: .5;
}

.palette-item:active {
  cursor: grabbing;
}

.item-icon {
  width: 24px;
  height: 24px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 6px;
  color: #fff;
}

.item-icon svg {
  width: 13px;
  height: 13px;
}

.item-label {
  min-width: 0;
  flex: 1 1 auto;
}

.item-label strong {
  display: block;
  overflow: hidden;
  color: #4e596c;
  font-size: 11px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.add-mark {
  margin-left: auto;
  color: #b5bdcb;
  font-size: 15px;
}

.added-mark {
  margin-left: auto;
  color: #a8b0bd;
  font-size: 9px;
}

.palette-empty {
  padding: 18px 8px;
  color: #9aa3b2;
  font-size: 11px;
  text-align: center;
}

.palette-footer {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 12px 14px;
  border-top: 1px solid #edeef3;
  color: #9aa3b2;
  font-size: 10px;
}

.canvas-viewport {
  position: relative;
  flex: 1;
  min-width: 0;
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

.canvas-toastbar {
  position: absolute;
  z-index: 20;
  top: 12px;
  left: 50%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 5px 12px;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(55, 62, 93, .13);
  transform: translateX(-50%);
}

.canvas-stage {
  position: absolute;
  width: 2200px;
  height: 1400px;
  transform-origin: 0 0;
}

.link-layer {
  position: absolute;
  inset: 0;
  overflow: visible;
  pointer-events: none;
}

.canvas-link {
  pointer-events: none;
  transition: stroke .12s ease, stroke-width .12s ease;
}

.canvas-link.selected {
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

.node-head {
  display: flex;
  box-sizing: border-box;
  align-items: center;
  gap: calc(7px * var(--node-scale));
  height: calc(38px * var(--node-scale));
  margin: 1.5%;
  padding: 0 calc(5px * var(--node-scale));
  border-bottom: 1px solid #f0f1f5;
  border-radius: 5px;
}

.node-icon {
  width: calc(22px * var(--node-scale));
  height: calc(22px * var(--node-scale));
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 6px;
  font-size: calc(13px * var(--node-scale));
}

.node-icon svg {
  width: calc(13px * var(--node-scale));
  height: calc(13px * var(--node-scale));
}

.node-head .node-title {
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
  color: #30394a;
  font-size: calc(11px * var(--node-scale));
  font-weight: 600;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: text;
}

.node-inline-input {
  min-width: 0;
  width: 100%;
  flex: 1 1 auto;
  border: 1px solid #c7c3f5;
  border-radius: 3px;
  outline: none;
  background: #fff;
  color: #394158;
  font-size: calc(10px * var(--node-scale));
  line-height: calc(18px * var(--node-scale));
  padding: 0 3px;
}

.node-value-input {
  max-width: 110px;
  margin-left: auto;
}

.node-run, .node-menu {
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  width: calc(18px * var(--node-scale));
  height: calc(18px * var(--node-scale));
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #8b95a8;
  cursor: pointer;
  transition: background .12s ease, color .12s ease;
}

.node-run svg {
  width: calc(10px * var(--node-scale));
  height: calc(10px * var(--node-scale));
}

.node-run:hover {
  background: #eef0ff;
  color: #5c52ee;
}

.node-menu {
  font-size: calc(15px * var(--node-scale));
}

.node-menu:hover {
  background: #fdecec;
  color: #d05050;
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

.port-row > span:not(.port):not(.status-icon) {
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
  cursor: text;
}

.port-label {
  cursor: text;
}

.required-mark {
  flex: 0 0 auto;
  color: #e11d48;
  font-size: calc(11px * var(--node-scale));
  font-weight: 700;
}

.status-row {
  gap: calc(6px * var(--node-scale));
  color: #9aa3b2;
  font-size: calc(10px * var(--node-scale));
}

.status-icon {
  width: calc(8px * var(--node-scale));
  height: calc(8px * var(--node-scale));
  flex: 0 0 auto;
  border: calc(1.5px * var(--node-scale)) solid #b3bac6;
  border-radius: 50%;
}

.output-row {
  justify-content: flex-end;
}

.output-row small {
  margin: 0 auto 0 0;
  cursor: text;
}

.port {
  position: relative;
  z-index: 5;
  width: calc(12px * var(--node-scale));
  height: calc(12px * var(--node-scale));
  flex: 0 0 auto;
  border: calc(2px * var(--node-scale)) solid #b4aff5;
  border-radius: 50%;
  background: #fff;
  cursor: crosshair;
  transition: transform .12s ease, background .12s ease, box-shadow .12s ease;
}

.port:hover, .port:active {
  transform: scale(1.25);
  background: #f2f0ff;
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

.canvas-controls {
  position: absolute;
  z-index: 20;
  bottom: 14px;
  left: 14px;
  display: flex;
  flex-direction: column;
  border: 1px solid #e1e5ed;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(55, 62, 93, .1);
  overflow: hidden;
}

.canvas-controls button {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-bottom: 1px solid #edeef3;
  background: #fff;
  color: #667186;
  cursor: pointer;
  font-size: 14px;
  transition: background .12s ease, color .12s ease;
}

.canvas-controls button:last-child {
  border-bottom: 0;
}

.canvas-controls button:hover {
  background: #f2f0ff;
  color: #5c52ee;
}

.canvas-controls svg {
  width: 13px;
  height: 13px;
  fill: currentColor;
}

.canvas-minimap {
  position: absolute;
  z-index: 20;
  right: 14px;
  bottom: 14px;
  padding: 5px;
  border: 1px solid #e1e5ed;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(55, 62, 93, .1);
}

.canvas-minimap svg {
  display: block;
}

.minimap-node {
  fill: rgba(102, 84, 238, .18);
  stroke: rgba(102, 84, 238, .4);
  stroke-width: 1;
}

.minimap-node.active {
  fill: rgba(102, 84, 238, .35);
}

.minimap-view {
  fill: rgba(38, 46, 74, .06);
  stroke: rgba(102, 84, 238, .55);
  stroke-dasharray: 3 2;
  stroke-width: 1;
}

.inspector {
  display: flex;
  flex-direction: column;
  width: 300px;
  flex: 0 0 300px;
  background: #fff;
  border-left: 1px solid #e1e5ed;
}

.inspector-head {
  display: flex;
  justify-content: space-between;
  padding: 15px 16px 12px;
  border-bottom: 1px solid #e8eaf0;
}

.inspector-head h2 {
  margin: 3px 0 0;
  font-size: 14px;
  font-weight: 600;
}

.inspector-scroll {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.inspector-content {
  padding: 16px;
}

.inspector-meta {
  margin-top: 18px;
  padding-top: 13px;
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
  height: 52%;
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

.inspector-stats {
  margin-top: auto;
  padding: 14px 16px 16px;
  border-top: 1px solid #e8eaf0;
}

.inspector-stats h4 {
  margin: 0 0 10px;
  color: #8b95a8;
  font-size: 10px;
  font-weight: 600;
}

.stat-row {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 6px;
  color: #667186;
  font-size: 11px;
}

.stat-row strong {
  margin-left: auto;
  color: #30394a;
  font-size: 11px;
}

.stat-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 auto;
  border-radius: 50%;
}

.stat-dot.all {
  background: #6654ee;
}

.stat-dot.done {
  background: #49b788;
}

.stat-dot.running {
  background: #4a90e2;
}

.stat-dot.pending {
  background: #c8cdd8;
}

.canvas-statusbar {
  display: flex;
  align-items: center;
  gap: 16px;
  height: 30px;
  flex: 0 0 30px;
  padding: 0 14px;
  background: #f2f3f7;
  border-top: 1px solid #e4e7ee;
  color: #8b95a8;
  font-size: 11px;
}

.statusbar-save.saved {
  color: #2f9e6f;
}

.flex-1 {
  flex: 1;
}

@media (max-width: 900px) {
  .inspector {
    display: none;
  }
}

@media (max-width: 640px) {
  .graph-header {
    padding: 0 12px;
  }

  .back-link span, .save-state, .autosave-control, .header-actions .n-button:first-of-type {
    display: none;
  }

  .node-palette {
    display: none;
  }
}
</style>
