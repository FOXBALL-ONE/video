package top.foxball.video.entity.jdbc.graph

/**
 * 节点目录中的一个端口（输入或输出插槽）定义。
 *
 * [handle] 为稳定英文标识，[label] 为界面显示名，[color] 为端口连线颜色，
 * [required] 表示该输入端口是否为必连端口（界面上以红色星号标记）。
 */
data class CatalogPort(
    val handle: String,
    val label: String,
    val color: String,
    val required: Boolean = false,
)

/**
 * 节点目录中的一个节点类型定义。
 *
 * [icon] 保存 lucide 风格的 SVG path 数据列表，前端直接以内联 SVG 渲染；
 * [singleton] 表示该类型在一张画布中只允许添加一个（例如剧本输入）。
 */
data class CatalogNodeType(
    val type: String,
    val title: String,
    val categoryKey: String,
    val color: String,
    val icon: List<String>,
    val singleton: Boolean = false,
    val inputs: List<CatalogPort> = emptyList(),
    val outputs: List<CatalogPort> = emptyList(),
)

/** 节点目录中的一个分类，例如「输入」「提取」。 */
data class CatalogCategory(
    val key: String,
    val label: String,
    val nodes: List<CatalogNodeType>,
)

/**
 * 画布节点类型目录。
 *
 * 这是前端左侧节点面板、节点端口渲染与连线上色的唯一数据源，
 * 通过 `GET /api/graphs/node-catalog` 暴露，不落库。
 */
object GraphNodeCatalog {

    private const val COLOR_SCRIPT = "#8b5cf6"
    private const val COLOR_CHARACTERS = "#ec4899"
    private const val COLOR_SCENES = "#14b8a6"
    private const val COLOR_PROPS = "#f59e0b"
    private const val COLOR_CHARACTER_IMAGES = "#e11d48"
    private const val COLOR_SCENE_IMAGES = "#0891b2"
    private const val COLOR_PROP_IMAGES = "#ca8a04"
    private const val COLOR_STORYBOARD = "#6366f1"
    private const val COLOR_GRIDS = "#a855f7"
    private const val COLOR_VIDEO_PROMPTS = "#f59e0b"
    private const val COLOR_GROUPS = "#9333ea"
    private const val COLOR_GROUP = "#a855f7"
    private const val COLOR_VIDEO_URLS = "#64748b"
    private const val COLOR_DUBBING_SCRIPT = "#16a34a"
    private const val COLOR_AUDIO_URLS = "#16a34a"
    private const val COLOR_COMPOSED_VIDEO = "#0284c7"

    private val portScriptText = CatalogPort("scriptText", "剧本文本", COLOR_SCRIPT, required = true)
    private val portCharacters = CatalogPort("characters", "角色列表", COLOR_CHARACTERS)
    private val portScenes = CatalogPort("scenes", "场景列表", COLOR_SCENES)
    private val portProps = CatalogPort("props", "道具列表", COLOR_PROPS)

    private val ICON_FILE_TEXT = listOf(
        "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z",
        "M14 2v4a2 2 0 0 0 2 2h4",
        "M10 9H8",
        "M16 13H8",
        "M16 17H8",
    )

    private val ICON_IMAGE_PLUS = listOf(
        "M16 5h6",
        "M19 2v6",
        "M21 11.5V19a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h7.5",
        "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
        "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )

    private val ICON_USER_SEARCH = listOf(
        "M6 7a4 4 0 1 0 8 0a4 4 0 1 0 -8 0",
        "M10.3 15H7a4 4 0 0 0-4 4v2",
        "M14 17a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "m21 21-1.9-1.9",
    )

    private val ICON_MAP_PIN = listOf(
        "M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0",
        "M9 10a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
    )

    private val ICON_PACKAGE = listOf(
        "M11 21.73a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73z",
        "M12 22V12",
        "m3.3 7 7.703 4.734a2 2 0 0 0 1.994 0L20.7 7",
        "m7.5 4.27 9 5.15",
    )

    private val ICON_PALETTE = listOf(
        "M13 6.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0",
        "M17 10.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0",
        "M8 7.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0",
        "M6 12.5a.5.5 0 1 0 1 0a.5.5 0 1 0 -1 0",
        "M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 0 1 1.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z",
    )

    private val ICON_IMAGE = listOf(
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
        "M7 9a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
        "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21",
    )

    private val ICON_PEN_TOOL = listOf(
        "M15.707 21.293a1 1 0 0 1-1.414 0l-1.586-1.586a1 1 0 0 1 0-1.414l5.586-5.586a1 1 0 0 1 1.414 0l1.586 1.586a1 1 0 0 1 0 1.414z",
        "m18 13-1.375-6.874a1 1 0 0 0-.746-.776L3.235 2.028a1 1 0 0 0-1.207 1.207L5.35 15.879a1 1 0 0 0 .776.746L13 18",
        "m2.3 2.3 7.286 7.286",
        "M9 11a2 2 0 1 0 4 0a2 2 0 1 0 -4 0",
    )

    private val ICON_LAYOUT_GRID = listOf(
        "M4 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z",
        "M15 3h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z",
        "M15 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Z",
        "M4 14h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1v-5a1 1 0 0 1 1-1Z",
    )

    private val ICON_GRID_3X3 = listOf(
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
        "M3 9h18",
        "M3 15h18",
        "M9 3v18",
        "M15 3v18",
    )

    private val ICON_SCISSORS = listOf(
        "M20 4 8.12 15.88",
        "M14.47 14.48 20 20",
        "M8.12 8.12 12 12",
        "M3 6a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
        "M3 18a3 3 0 1 0 6 0a3 3 0 1 0 -6 0",
    )

    private val ICON_ZOOM_IN = listOf(
        "M3 11a8 8 0 1 0 16 0a8 8 0 1 0 -16 0",
        "m21 21-4.3-4.3",
        "M11 8v6",
        "M8 11h6",
    )

    private val ICON_TYPE = listOf(
        "M4 7V4h16v3",
        "M9 20h6",
        "M12 4v16",
    )

    private val ICON_FILM = listOf(
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z",
        "M7 3v18",
        "M3 7.5h4",
        "M3 12h18",
        "M3 16.5h4",
        "M17 3v18",
        "M17 7.5h4",
        "M17 16.5h4",
    )

    private val ICON_VIDEO = listOf(
        "m16 13 5.223 3.482a.5.5 0 0 0 .777-.416V7.87a.5.5 0 0 0-.752-.432L16 10.5",
        "M4 6h10a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z",
    )

    private val ICON_SCROLL_TEXT = listOf(
        "M15 12h-5",
        "M15 8h-5",
        "M19 17V5a2 2 0 0 0-2-2H4",
        "M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3",
    )

    private val ICON_VOLUME_2 = listOf(
        "M11 4.702a.705.705 0 0 0-1.203-.498L6.413 7.587A1.4 1.4 0 0 1 5.416 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.416a1.4 1.4 0 0 1 .997.413l3.383 3.384A.705.705 0 0 0 11 19.298z",
        "M16 9a5 5 0 0 1 0 6",
        "M19.364 18.364a9 9 0 0 0 0-12.728",
    )

    private val ICON_LAYERS = listOf(
        "m12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
        "m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65",
        "m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65",
    )

    private val ICON_DOWNLOAD = listOf(
        "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4",
        "m7 10 5 5 5-5",
        "M12 15V3",
    )

    val categories: List<CatalogCategory> = listOf(
        CatalogCategory(
            "input",
            "输入",
            listOf(
                CatalogNodeType(
                    "script_input",
                    "剧本输入",
                    "input",
                    COLOR_SCRIPT,
                    ICON_FILE_TEXT,
                    singleton = true,
                    outputs = listOf(CatalogPort("scriptText", "剧本文本", COLOR_SCRIPT)),
                ),
                CatalogNodeType(
                    "image_import",
                    "图片导入",
                    "input",
                    "#6366f1",
                    ICON_IMAGE_PLUS,
                    outputs = listOf(CatalogPort("images", "图片列表", COLOR_STORYBOARD)),
                ),
            ),
        ),
        CatalogCategory(
            "extract",
            "提取",
            listOf(
                CatalogNodeType(
                    "character_extract",
                    "角色提取",
                    "extract",
                    COLOR_CHARACTERS,
                    ICON_USER_SEARCH,
                    inputs = listOf(portScriptText),
                    outputs = listOf(CatalogPort("characters", "角色列表", COLOR_CHARACTERS)),
                ),
                CatalogNodeType(
                    "scene_extract",
                    "场景提取",
                    "extract",
                    COLOR_SCENES,
                    ICON_MAP_PIN,
                    inputs = listOf(portScriptText),
                    outputs = listOf(CatalogPort("scenes", "场景列表", COLOR_SCENES)),
                ),
                CatalogNodeType(
                    "prop_extract",
                    "道具提取",
                    "extract",
                    COLOR_PROPS,
                    ICON_PACKAGE,
                    inputs = listOf(portScriptText),
                    outputs = listOf(CatalogPort("props", "道具列表", COLOR_PROPS)),
                ),
            ),
        ),
        CatalogCategory(
            "art",
            "美术",
            listOf(
                CatalogNodeType(
                    "character_art",
                    "角色美术",
                    "art",
                    COLOR_CHARACTER_IMAGES,
                    ICON_PALETTE,
                    inputs = listOf(CatalogPort("characters", "角色列表", COLOR_CHARACTERS, required = true)),
                    outputs = listOf(CatalogPort("characterImages", "角色图片", COLOR_CHARACTER_IMAGES)),
                ),
                CatalogNodeType(
                    "scene_art",
                    "场景美术",
                    "art",
                    COLOR_SCENE_IMAGES,
                    ICON_IMAGE,
                    inputs = listOf(CatalogPort("scenes", "场景列表", COLOR_SCENES, required = true)),
                    outputs = listOf(CatalogPort("sceneImages", "场景图片", COLOR_SCENE_IMAGES)),
                ),
                CatalogNodeType(
                    "prop_art",
                    "道具美术",
                    "art",
                    COLOR_PROP_IMAGES,
                    ICON_PEN_TOOL,
                    inputs = listOf(portProps),
                    outputs = listOf(CatalogPort("propImages", "道具图片", COLOR_PROP_IMAGES)),
                ),
            ),
        ),
        CatalogCategory(
            "storyboard",
            "分镜",
            listOf(
                CatalogNodeType(
                    "storyboard_plan",
                    "分镜规划",
                    "storyboard",
                    COLOR_STORYBOARD,
                    ICON_LAYOUT_GRID,
                    inputs = listOf(
                        portScriptText,
                        CatalogPort("characters", "角色列表", COLOR_CHARACTERS, required = true),
                        CatalogPort("scenes", "场景列表", COLOR_SCENES, required = true),
                        portProps,
                    ),
                    outputs = listOf(CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD)),
                ),
                CatalogNodeType(
                    "grid_generate",
                    "九宫格生成",
                    "storyboard",
                    COLOR_GRIDS,
                    ICON_GRID_3X3,
                    inputs = listOf(CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD, required = true)),
                    outputs = listOf(CatalogPort("grids", "九宫格", COLOR_GRIDS)),
                ),
                CatalogNodeType(
                    "grid_batch",
                    "单批九宫格",
                    "storyboard",
                    COLOR_GRIDS,
                    ICON_GRID_3X3,
                    inputs = listOf(CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD, required = true)),
                    outputs = listOf(CatalogPort("grids", "九宫格", COLOR_GRIDS)),
                ),
                CatalogNodeType(
                    "image_split",
                    "切图",
                    "storyboard",
                    COLOR_SCENES,
                    ICON_SCISSORS,
                    inputs = listOf(CatalogPort("grids", "九宫格", COLOR_GRIDS)),
                    outputs = listOf(CatalogPort("images", "镜头图", COLOR_SCENES)),
                ),
                CatalogNodeType(
                    "image_upscale",
                    "画质放大",
                    "storyboard",
                    "#0ea5e9",
                    ICON_ZOOM_IN,
                    inputs = listOf(CatalogPort("images", "镜头图", COLOR_SCENES)),
                    outputs = listOf(CatalogPort("images", "高清图", "#0ea5e9")),
                ),
                CatalogNodeType(
                    "video_prompt",
                    "视频提示词",
                    "storyboard",
                    COLOR_VIDEO_PROMPTS,
                    ICON_TYPE,
                    inputs = listOf(CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD)),
                    outputs = listOf(CatalogPort("videoPrompts", "视频提示词", COLOR_VIDEO_PROMPTS)),
                ),
            ),
        ),
        CatalogCategory(
            "seedance",
            "S2.0",
            listOf(
                CatalogNodeType(
                    "seedance2_prompt",
                    "S2.0 提示词",
                    "seedance",
                    COLOR_GROUPS,
                    ICON_FILE_TEXT,
                    inputs = listOf(
                        CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD, required = true),
                        CatalogPort("characterImages", "角色图片", COLOR_CHARACTER_IMAGES, required = true),
                        CatalogPort("sceneImages", "场景图片", COLOR_SCENE_IMAGES),
                        CatalogPort("propImages", "道具图片", COLOR_PROP_IMAGES),
                    ),
                    outputs = listOf(
                        CatalogPort("groups", "分组列表", COLOR_GROUPS),
                        CatalogPort("group", "单组", COLOR_GROUP),
                    ),
                ),
                CatalogNodeType(
                    "seedance2_video",
                    "S2.0 视频（批量）",
                    "seedance",
                    COLOR_GROUPS,
                    ICON_FILM,
                    inputs = listOf(
                        CatalogPort("groups", "分组列表", COLOR_GROUPS, required = true),
                        CatalogPort("characterImages", "角色图片", COLOR_CHARACTER_IMAGES, required = true),
                        CatalogPort("sceneImages", "场景图片", COLOR_SCENE_IMAGES),
                        CatalogPort("propImages", "道具图片", COLOR_PROP_IMAGES),
                    ),
                    outputs = listOf(CatalogPort("groups", "分组列表", COLOR_GROUPS)),
                ),
                CatalogNodeType(
                    "seedance2_video_single",
                    "S2.0 单组视频",
                    "seedance",
                    COLOR_GROUP,
                    ICON_FILM,
                    inputs = listOf(
                        CatalogPort("group", "单组", COLOR_GROUP, required = true),
                        CatalogPort("characterImages", "角色图片", COLOR_CHARACTER_IMAGES, required = true),
                        CatalogPort("sceneImages", "场景图片", COLOR_SCENE_IMAGES),
                        CatalogPort("propImages", "道具图片", COLOR_PROP_IMAGES),
                    ),
                    outputs = listOf(CatalogPort("videoUrls", "视频片段", COLOR_VIDEO_URLS)),
                ),
            ),
        ),
        CatalogCategory(
            "video",
            "视频",
            listOf(
                CatalogNodeType(
                    "shot_video",
                    "逐镜头视频",
                    "video",
                    COLOR_SCENE_IMAGES,
                    ICON_VIDEO,
                    inputs = listOf(CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD)),
                    outputs = listOf(CatalogPort("videoUrls", "视频片段", COLOR_VIDEO_URLS)),
                ),
                CatalogNodeType(
                    "video_clip",
                    "视频片段",
                    "video",
                    COLOR_VIDEO_URLS,
                    ICON_VIDEO,
                    inputs = listOf(CatalogPort("videoPrompts", "视频提示词", COLOR_VIDEO_PROMPTS)),
                    outputs = listOf(CatalogPort("videoUrls", "视频片段", COLOR_VIDEO_URLS)),
                ),
                CatalogNodeType(
                    "shot_clip",
                    "单镜视频片段",
                    "video",
                    "#0ea5e9",
                    ICON_FILM,
                    inputs = listOf(CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD)),
                    outputs = listOf(CatalogPort("videoUrls", "视频片段", COLOR_VIDEO_URLS)),
                ),
            ),
        ),
        CatalogCategory(
            "audio",
            "音频",
            listOf(
                CatalogNodeType(
                    "dubbing_script",
                    "配音脚本",
                    "audio",
                    COLOR_DUBBING_SCRIPT,
                    ICON_SCROLL_TEXT,
                    inputs = listOf(CatalogPort("scriptText", "剧本文本", COLOR_SCRIPT)),
                    outputs = listOf(CatalogPort("dubbingScript", "配音脚本", COLOR_DUBBING_SCRIPT)),
                ),
                CatalogNodeType(
                    "voice_synthesis",
                    "语音合成",
                    "audio",
                    COLOR_AUDIO_URLS,
                    ICON_VOLUME_2,
                    inputs = listOf(CatalogPort("dubbingScript", "配音脚本", COLOR_DUBBING_SCRIPT)),
                    outputs = listOf(CatalogPort("audioUrls", "音频文件", COLOR_AUDIO_URLS)),
                ),
            ),
        ),
        CatalogCategory(
            "output",
            "输出",
            listOf(
                CatalogNodeType(
                    "compose",
                    "合成导出",
                    "output",
                    COLOR_COMPOSED_VIDEO,
                    ICON_LAYERS,
                    inputs = listOf(
                        CatalogPort("storyboard", "分镜表", COLOR_STORYBOARD),
                        CatalogPort("groups", "分组", COLOR_GROUPS),
                        CatalogPort("videoUrls", "视频片段", COLOR_VIDEO_URLS),
                        CatalogPort("audioUrls", "音频文件", COLOR_AUDIO_URLS),
                    ),
                    outputs = listOf(CatalogPort("composedVideoUrl", "成片URL", COLOR_COMPOSED_VIDEO)),
                ),
                CatalogNodeType(
                    "asset_export",
                    "资产导出",
                    "output",
                    "#f97316",
                    ICON_DOWNLOAD,
                    inputs = listOf(
                        CatalogPort("characterImages", "角色图片", COLOR_CHARACTER_IMAGES),
                        CatalogPort("sceneImages", "场景图片", COLOR_SCENE_IMAGES),
                        CatalogPort("propImages", "道具图片", COLOR_PROP_IMAGES),
                    ),
                ),
            ),
        ),
    )
}
