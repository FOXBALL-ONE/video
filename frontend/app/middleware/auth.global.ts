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
        if (token.value && user.value) return navigateTo("/graph");
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
});
