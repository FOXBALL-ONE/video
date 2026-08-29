import {computed, ref} from "vue";
import {defineStore} from "pinia";

export interface WorkspaceActivity { title: string; time: string; dot: string }
export interface WorkspaceProject {
    id: string;
    code: string;
    name: string;
    type: string;
    description: string;
    status: string;
    progress: number;
    version: string;
    duration: string;
    score: string;
    updatedAt: string;
    editor: string;
    pending: string;
    pendingDetail: string;
    activities: WorkspaceActivity[];
}

const defaultProjects: WorkspaceProject[] = [
    {
        id: "rain-letter", code: "01", name: "雨夜来信", type: "悬疑短片",
        description: "失意摄影师在雨夜收到来自十年前自己的信。", status: "制作中", progress: 68,
        version: "v1.4", duration: "2:36", score: "9.1", updatedAt: "今天 14:32", editor: "林夏",
        pending: "3 项待处理", pendingDetail: "顾川三视图、旧信封版权来源、林夏声线接口。",
        activities: [
            {title: "完成剧本结构化，识别 8 场戏和 42 句对白", time: "14:32 · 剧本输入", dot: "bg-[#6654ee]"},
            {title: "新增资产缺口提醒，等待补充 3 项信息", time: "14:18 · 资产清单", dot: "bg-[#e6a23c]"},
            {title: "镜头 02 连续性检查标记为待优化", time: "13:56 · 分镜管理", dot: "bg-[#e6a23c]"},
        ],
    },
    {
        id: "city-light", code: "02", name: "城市微光", type: "纪录短片",
        description: "记录城市夜班人群，在微光里寻找彼此的生活轨迹。", status: "制作中", progress: 34,
        version: "v0.8", duration: "1:48", score: "8.4", updatedAt: "昨天 19:06", editor: "周野",
        pending: "5 项待处理", pendingDetail: "补录两段采访、确认环境音版权、整理夜景素材。",
        activities: [
            {title: "导入 12 段采访素材，完成初步分组", time: "19:06 · 资产清单", dot: "bg-[#6654ee]"},
            {title: "采访提纲新增 4 个问题", time: "18:42 · 剧本输入", dot: "bg-[#6654ee]"},
            {title: "发现 2 段素材需要补录环境音", time: "17:15 · 视频制作", dot: "bg-[#e6a23c]"},
        ],
    },
    {
        id: "silent-orbit", code: "03", name: "静默轨道", type: "科幻概念片",
        description: "一艘失联飞船在深空中收到最后一条人类讯息。", status: "草稿", progress: 12,
        version: "v0.3", duration: "0:42", score: "7.6", updatedAt: "8 月 25 日 11:20", editor: "顾川",
        pending: "8 项待处理", pendingDetail: "补充世界观设定、确定角色声线、建立首版分镜。",
        activities: [
            {title: "建立项目并完成故事梗概", time: "11:20 · 剧本输入", dot: "bg-[#6654ee]"},
            {title: "新增 6 条视觉参考资产", time: "10:54 · 资产清单", dot: "bg-[#6654ee]"},
            {title: "项目仍在等待首版分镜", time: "10:40 · 分镜管理", dot: "bg-[#e6a23c]"},
        ],
    },
];

function cloneProjects(): WorkspaceProject[] {
    return defaultProjects.map((project) => ({...project, activities: project.activities.map((activity) => ({...activity}))}));
}

export const useWorkspaceStore = defineStore("workspace", () => {
    const projects = ref<WorkspaceProject[]>(cloneProjects());
    const activeProjectId = ref(defaultProjects[0].id);
    const activeProject = computed(() => projects.value.find((project) => project.id === activeProjectId.value) || projects.value[0]);

    function switchProject(projectId: string) {
        if (projects.value.some((project) => project.id === projectId)) activeProjectId.value = projectId;
    }

    function createProject(input: {name: string; type: string; description?: string}) {
        const nextNumber = projects.value.length + 1;
        const project: WorkspaceProject = {
            id: `project-${Date.now()}`, code: String(nextNumber).padStart(2, "0"), name: input.name,
            type: input.type, description: input.description || "新建制作项目，等待补充项目简介。", status: "草稿",
            progress: 0, version: "v0.1", duration: "0:00", score: "--", updatedAt: "刚刚", editor: "当前用户",
            pending: "待规划", pendingDetail: "从剧本输入开始建立制作流程。",
            activities: [{title: "创建项目，等待开始制作", time: "刚刚 · 项目管理", dot: "bg-[#6654ee]"}],
        };
        projects.value.push(project);
        activeProjectId.value = project.id;
        return project;
    }

    return {projects, activeProjectId, activeProject, switchProject, createProject};
});
