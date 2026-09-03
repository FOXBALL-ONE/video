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

export interface ApiProject {
    id: number;
    code: string;
    name: string;
    type: string;
    description: string;
    status: string;
    progress: number;
    version: string;
    duration_seconds: number;
    quality_score: number | null;
    editor: string | null;
    pending_count: number;
    pending_detail: string | null;
    updated_at: string | null;
}

export interface ApiProjectDetail extends ApiProject {
    current_stage: string;
    script_status: string;
    asset_status: string;
    shot_status: string;
    video_status: string;
    started_at: string | null;
    completed_at: string | null;
    archived_at: string | null;
    created_at: string | null;
    activities: Array<{ title: string; stage: string | null; created_at: string }>;
}

export const useWorkspaceStore = defineStore("workspace", () => {
    const projects = ref<WorkspaceProject[]>([]);
    const activeProjectId = ref<string | null>(null);
    const activeProject = computed(() => projects.value.find((project) => project.id === activeProjectId.value) || projects.value[0] || null);

    function switchProject(projectId: string) {
        if (projects.value.some((project) => project.id === projectId)) activeProjectId.value = projectId;
    }

    function setProjects(nextProjects: WorkspaceProject[]) {
        projects.value = nextProjects;
        if (!projects.value.some((project) => project.id === activeProjectId.value)) activeProjectId.value = projects.value[0]?.id || null;
    }

    function addProject(project: WorkspaceProject) {
        projects.value = [...projects.value.filter((item) => item.id !== project.id), project];
        activeProjectId.value = project.id;
    }

    return {projects, activeProjectId, activeProject, switchProject, setProjects, addProject};
});
