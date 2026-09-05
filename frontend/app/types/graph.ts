/** 画布节点目录与画布数据的类型定义。 */

/** 节点目录中的一个端口（输入或输出插槽）定义。 */
export interface CatalogPort {
  handle: string
  label: string
  color: string
  required?: boolean
}

/** 节点目录中的一个节点类型定义。 */
export interface CatalogNodeType {
  type: string
  title: string
  category_key: string
  color: string
  /** lucide 风格 SVG path 数据列表，直接内联渲染。 */
  icon: string[]
  singleton?: boolean
  inputs: CatalogPort[]
  outputs: CatalogPort[]
}

/** 节点目录中的一个分类。 */
export interface CatalogCategory {
  key: string
  label: string
  node_count?: number
  nodes: CatalogNodeType[]
}

export type CanvasNode = {
  id: string
  type: string
  title: string
  icon: string
  color: string
  x: number
  y: number
  width: number
  height: number
  order: number
  mode: '启用' | '旁路' | '禁用'
  note: string
  inputs: Array<Record<string, any>>
  outputs: Array<Record<string, any>>
}

/** 后端节点目录不可用时的本地回退目录，与 GraphNodeCatalog.kt 保持一致。 */
export const GRAPH_NODE_CATALOG_FALLBACK: CatalogCategory[] = [
  {
    key: 'input',
    label: '输入',
    nodes: [
      {
        type: 'script_input', title: '剧本输入', category_key: 'input', color: '#8b5cf6', singleton: true,
        icon: ['M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z', 'M14 2v4a2 2 0 0 0 2 2h4', 'M10 9H8', 'M16 13H8', 'M16 17H8'],
        inputs: [],
        outputs: [{handle: 'scriptText', label: '剧本文本', color: '#8b5cf6'}],
      },
      {
        type: 'image_import', title: '图片导入', category_key: 'input', color: '#6366f1',
        icon: ['M16 5h6', 'M19 2v6', 'M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5', 'm21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21', 'M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0'],
        inputs: [],
        outputs: [{handle: 'images', label: '图片列表', color: '#6366f1'}],
      },
    ],
  },
  {
    key: 'extract',
    label: '提取',
    nodes: [
      {
        type: 'character_extract', title: '角色提取', category_key: 'extract', color: '#ec4899',
        icon: ['M6 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0', 'M10.3 15H7a4 4 0 0 0-4 4v2', 'M14 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0', 'm21 21-1.9-1.9'],
        inputs: [{handle: 'scriptText', label: '剧本文本', color: '#8b5cf6', required: true}],
        outputs: [{handle: 'characters', label: '角色列表', color: '#ec4899'}],
      },
      {
        type: 'scene_extract', title: '场景提取', category_key: 'extract', color: '#14b8a6',
        icon: ['M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0', 'M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0'],
        inputs: [{handle: 'scriptText', label: '剧本文本', color: '#8b5cf6', required: true}],
        outputs: [{handle: 'scenes', label: '场景列表', color: '#14b8a6'}],
      },
      {
        type: 'prop_extract', title: '道具提取', category_key: 'extract', color: '#f59e0b',
        icon: ['M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z', 'M12 22V12', 'm3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7', 'm7.5 4.27 9 5.15'],
        inputs: [{handle: 'scriptText', label: '剧本文本', color: '#8b5cf6', required: true}],
        outputs: [{handle: 'props', label: '道具列表', color: '#f59e0b'}],
      },
    ],
  },
  {
    key: 'art',
    label: '美术',
    nodes: [
      {
        type: 'character_art', title: '角色美术', category_key: 'art', color: '#e11d48',
        icon: ['M13 6.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0', 'M17 10.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0', 'M8 7.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0', 'M6 12.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0', 'M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z'],
        inputs: [{handle: 'characters', label: '角色列表', color: '#ec4899', required: true}],
        outputs: [{handle: 'characterImages', label: '角色图片', color: '#e11d48'}],
      },
      {
        type: 'scene_art', title: '场景美术', category_key: 'art', color: '#0891b2',
        icon: ['M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0', 'm21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21'],
        inputs: [{handle: 'scenes', label: '场景列表', color: '#14b8a6', required: true}],
        outputs: [{handle: 'sceneImages', label: '场景图片', color: '#0891b2'}],
      },
      {
        type: 'prop_art', title: '道具美术', category_key: 'art', color: '#ca8a04',
        icon: ['M15.707 21.293a1 1 0 0 1-1.414 0l-1.586-1.586a1 1 0 0 1 0-1.414l5.586-5.586a1 1 0 0 1 1.414 0l1.586 1.586a1 1 0 0 1 0 1.414z', 'm18 13-1.375-6.874a1 1 0 0 0-.746-.776L3.235 2.028a1 1 0 0 0-1.207 1.207L5.35 15.879a1 1 0 0 0 .776.746L13 18', 'm2.3 2.3 7.286 7.286', 'M9 11a2 2 0 1 0 4 0a2 2 0 1 0 -4 0'],
        inputs: [{handle: 'props', label: '道具列表', color: '#f59e0b'}],
        outputs: [{handle: 'propImages', label: '道具图片', color: '#ca8a04'}],
      },
    ],
  },
  {
    key: 'storyboard',
    label: '分镜',
    nodes: [
      {
        type: 'storyboard_plan', title: '分镜规划', category_key: 'storyboard', color: '#6366f1',
        icon: ['M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z', 'M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Z', 'M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Z'],
        inputs: [
          {handle: 'scriptText', label: '剧本文本', color: '#8b5cf6', required: true},
          {handle: 'characters', label: '角色列表', color: '#ec4899', required: true},
          {handle: 'scenes', label: '场景列表', color: '#14b8a6', required: true},
          {handle: 'props', label: '道具列表', color: '#f59e0b'},
        ],
        outputs: [{handle: 'storyboard', label: '分镜表', color: '#6366f1'}],
      },
      {
        type: 'grid_generate', title: '九宫格生成', category_key: 'storyboard', color: '#a855f7',
        icon: ['M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M3 9h18', 'M3 15h18', 'M9 3v18', 'M15 3v18'],
        inputs: [{handle: 'storyboard', label: '分镜表', color: '#6366f1', required: true}],
        outputs: [{handle: 'grids', label: '九宫格', color: '#a855f7'}],
      },
      {
        type: 'grid_batch', title: '单批九宫格', category_key: 'storyboard', color: '#a855f7',
        icon: ['M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M3 9h18', 'M3 15h18', 'M9 3v18', 'M15 3v18'],
        inputs: [{handle: 'storyboard', label: '分镜表', color: '#6366f1', required: true}],
        outputs: [{handle: 'grids', label: '九宫格', color: '#a855f7'}],
      },
      {
        type: 'image_split', title: '切图', category_key: 'storyboard', color: '#14b8a6',
        icon: ['M20 4 8.12 15.88', 'M14.47 14.48 20 20', 'M8.12 8.12 12 12', 'M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0', 'M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0'],
        inputs: [{handle: 'grids', label: '九宫格', color: '#a855f7'}],
        outputs: [{handle: 'images', label: '镜头图', color: '#14b8a6'}],
      },
      {
        type: 'image_upscale', title: '画质放大', category_key: 'storyboard', color: '#0ea5e9',
        icon: ['M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0', 'm21 21-4.3-4.3', 'M11 8v6', 'M8 11h6'],
        inputs: [{handle: 'images', label: '镜头图', color: '#14b8a6'}],
        outputs: [{handle: 'images', label: '高清图', color: '#0ea5e9'}],
      },
      {
        type: 'video_prompt', title: '视频提示词', category_key: 'storyboard', color: '#f59e0b',
        icon: ['M4 7V4h16v3', 'M9 20h6', 'M12 4v16'],
        inputs: [{handle: 'storyboard', label: '分镜表', color: '#6366f1'}],
        outputs: [{handle: 'videoPrompts', label: '视频提示词', color: '#f59e0b'}],
      },
    ],
  },
  {
    key: 'seedance',
    label: 'S2.0',
    nodes: [
      {
        type: 'seedance2_prompt', title: 'S2.0 提示词', category_key: 'seedance', color: '#9333ea',
        icon: ['M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z', 'M14 2v4a2 2 0 0 0 2 2h4', 'M10 9H8', 'M16 13H8', 'M16 17H8'],
        inputs: [
          {handle: 'storyboard', label: '分镜表', color: '#6366f1', required: true},
          {handle: 'characterImages', label: '角色图片', color: '#e11d48', required: true},
          {handle: 'sceneImages', label: '场景图片', color: '#0891b2'},
          {handle: 'propImages', label: '道具图片', color: '#ca8a04'},
        ],
        outputs: [
          {handle: 'groups', label: '分组列表', color: '#9333ea'},
          {handle: 'group', label: '单组', color: '#a855f7'},
        ],
      },
      {
        type: 'seedance2_video', title: 'S2.0 视频（批量）', category_key: 'seedance', color: '#9333ea',
        icon: ['M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M7 3v18', 'M3 7.5h4', 'M3 12h18', 'M3 16.5h4', 'M17 3v18', 'M17 7.5h4', 'M17 16.5h4'],
        inputs: [
          {handle: 'groups', label: '分组列表', color: '#9333ea', required: true},
          {handle: 'characterImages', label: '角色图片', color: '#e11d48', required: true},
          {handle: 'sceneImages', label: '场景图片', color: '#0891b2'},
          {handle: 'propImages', label: '道具图片', color: '#ca8a04'},
        ],
        outputs: [{handle: 'groups', label: '分组列表', color: '#9333ea'}],
      },
      {
        type: 'seedance2_video_single', title: 'S2.0 单组视频', category_key: 'seedance', color: '#a855f7',
        icon: ['M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M7 3v18', 'M3 7.5h4', 'M3 12h18', 'M3 16.5h4', 'M17 3v18', 'M17 7.5h4', 'M17 16.5h4'],
        inputs: [
          {handle: 'group', label: '单组', color: '#a855f7', required: true},
          {handle: 'characterImages', label: '角色图片', color: '#e11d48', required: true},
          {handle: 'sceneImages', label: '场景图片', color: '#0891b2'},
          {handle: 'propImages', label: '道具图片', color: '#ca8a04'},
        ],
        outputs: [{handle: 'videoUrls', label: '视频片段', color: '#64748b'}],
      },
    ],
  },
  {
    key: 'video',
    label: '视频',
    nodes: [
      {
        type: 'shot_video', title: '逐镜头视频', category_key: 'video', color: '#0891b2',
        icon: ['m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5', 'M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z'],
        inputs: [{handle: 'storyboard', label: '分镜表', color: '#6366f1'}],
        outputs: [{handle: 'videoUrls', label: '视频片段', color: '#64748b'}],
      },
      {
        type: 'video_clip', title: '视频片段', category_key: 'video', color: '#64748b',
        icon: ['m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5', 'M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z'],
        inputs: [{handle: 'videoPrompts', label: '视频提示词', color: '#f59e0b'}],
        outputs: [{handle: 'videoUrls', label: '视频片段', color: '#64748b'}],
      },
      {
        type: 'shot_clip', title: '单镜视频片段', category_key: 'video', color: '#0ea5e9',
        icon: ['M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z', 'M7 3v18', 'M3 7.5h4', 'M3 12h18', 'M3 16.5h4', 'M17 3v18', 'M17 7.5h4', 'M17 16.5h4'],
        inputs: [{handle: 'storyboard', label: '分镜表', color: '#6366f1'}],
        outputs: [{handle: 'videoUrls', label: '视频片段', color: '#64748b'}],
      },
    ],
  },
  {
    key: 'audio',
    label: '音频',
    nodes: [
      {
        type: 'dubbing_script', title: '配音脚本', category_key: 'audio', color: '#16a34a',
        icon: ['M15 12h-5', 'M15 8h-5', 'M19 17V5a2 2 0 0 0-2-2H4', 'M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3'],
        inputs: [{handle: 'scriptText', label: '剧本文本', color: '#8b5cf6'}],
        outputs: [{handle: 'dubbingScript', label: '配音脚本', color: '#16a34a'}],
      },
      {
        type: 'voice_synthesis', title: '语音合成', category_key: 'audio', color: '#16a34a',
        icon: ['M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z', 'M16 9a5 5 0 0 1 0 6', 'M19.364 18.364a9 9 0 0 0 0-12.728'],
        inputs: [{handle: 'dubbingScript', label: '配音脚本', color: '#16a34a'}],
        outputs: [{handle: 'audioUrls', label: '音频文件', color: '#16a34a'}],
      },
    ],
  },
  {
    key: 'output',
    label: '输出',
    nodes: [
      {
        type: 'compose', title: '合成导出', category_key: 'output', color: '#0284c7',
        icon: ['m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z', 'm22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65', 'm22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65'],
        inputs: [
          {handle: 'storyboard', label: '分镜表', color: '#6366f1'},
          {handle: 'groups', label: '分组', color: '#9333ea'},
          {handle: 'videoUrls', label: '视频片段', color: '#64748b'},
          {handle: 'audioUrls', label: '音频文件', color: '#16a34a'},
        ],
        outputs: [{handle: 'composedVideoUrl', label: '成片URL', color: '#0284c7'}],
      },
      {
        type: 'asset_export', title: '资产导出', category_key: 'output', color: '#f97316',
        icon: ['M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4', 'm7 10 5 5 5-5', 'M12 15V3'],
        inputs: [
          {handle: 'characterImages', label: '角色图片', color: '#e11d48'},
          {handle: 'sceneImages', label: '场景图片', color: '#0891b2'},
          {handle: 'propImages', label: '道具图片', color: '#ca8a04'},
        ],
        outputs: [],
      },
    ],
  },
]

/** 按类型标识从目录中查找节点定义。 */
export function resolveNodeDef(catalog: CatalogCategory[], type: string): CatalogNodeType | null {
  for (const category of catalog) {
    const found = category.nodes.find((node) => node.type === type)
    if (found) return found
  }
  return null
}
