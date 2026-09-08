import { useAuth } from '~/composables/useAuth'

export default defineNuxtRouteMiddleware(async (to) => {
    const {token, user, loadCurrentUser} = useAuth();

    if (to.path === "/login") {
        if (token.value && !user.value) {
            try {
                await loadCurrentUser();
            } catch {
                token.value = null;
            }
        }
        if (token.value && user.value) return navigateTo("/");
        return;
    }

    if (!token.value) return navigateTo("/login");
    if (!user.value) {
        try {
            await loadCurrentUser();
        } catch {
            token.value = null;
            return navigateTo({path: "/login", query: {redirect: to.fullPath}});
        }
    }

    if (to.path === "/graph/projects") return navigateTo("/projects");
    if (to.path === "/graph" || to.path === "/graph/overview" || to.path === "/graph/index") {
        const {activeProjectId} = useWorkspaceStore();
        return navigateTo(activeProjectId.value ? `/projects/${activeProjectId.value}/canvas` : "/projects");
    }
    const legacyStage = {script: "script", tasks: "tasks", assets: "assets", shots: "shots", storyboard: "storyboard", video: "video"}[to.path.slice("/graph/".length)];
    if (legacyStage) {
        const {activeProjectId} = useWorkspaceStore();
        return navigateTo(activeProjectId.value ? `/projects/${activeProjectId.value}/workflow/${legacyStage}` : "/projects");
    }
});
