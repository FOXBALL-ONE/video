package top.foxball.video.entity.jdbc.graph

/** ComfyUI 画布记录的类型。 */
enum class GraphKind {
    /** 项目工作流的根画布。 */
    ROOT,
    /** definitions.subgraphs 中的子图内部画布。 */
    SUBGRAPH,
}

/** ComfyUI 序列化 ID 的原始 JSON 类型。 */
enum class SerializedIdKind {
    /** 原始 JSON 使用 number。 */
    NUMBER,
    /** 原始 JSON 使用 string。 */
    STRING,
}

/** 0.4 使用数组连线，1.x 使用对象连线。 */
enum class SerializedLinkKind {
    /** ComfyUI 0.4 普通 links 使用的六元数组格式。 */
    ARRAY,
    /** ComfyUI 1.x 与 floatingLinks 使用的对象格式。 */
    OBJECT,
}

/** ComfyUI 当前使用的画布渲染器版本。 */
enum class GraphRendererVersion {
    /** LiteGraph Canvas 原生节点渲染。 */
    LG,
    /** Vue 节点渲染的旧版布局标识。 */
    VUE,
    /** Vue 节点渲染并完成布局缩放校正后的标识。 */
    VUE_CORRECTED,
}
