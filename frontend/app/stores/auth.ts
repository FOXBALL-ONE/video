import {computed, ref, watch} from "vue";
import {defineStore} from "pinia";
import type {AuthUser} from "~/types/auth";
import {useHttp} from "~/composables/useHttp";

interface LoginData { access_token: string; token_type: string }

export const useAuthStore = defineStore("auth", () => {
    const token = useCookie<string | null>("chat_auth_token", {maxAge: 60 * 60 * 24 * 7, sameSite: "lax", path: "/"});
    const user = ref<AuthUser | null>(null);
    const loading = ref(false);
    const isAuthenticated = computed(() => Boolean(token.value));
    const {post, get} = useHttp();

    watch(token, (value) => { if (!value) user.value = null; });

    async function login(username: string, password: string) {
        loading.value = true;
        try {
            const result = await post<LoginData, {username: string; password: string}>("/auth/login", {username, password});
            token.value = result.access_token;
            user.value = await get<AuthUser>("/auth/me");
            return user.value;
        } finally { loading.value = false; }
    }

    async function register(username: string, email: string, password: string) {
        loading.value = true;
        try { return await post<{user_id: number}>("/auth/register", {username, email, password}); }
        finally { loading.value = false; }
    }

    async function loadCurrentUser() {
        if (!token.value) { user.value = null; return null; }
        user.value = await get<AuthUser>("/auth/me");
        return user.value;
    }

    async function logout() {
        try { if (token.value) await post("/auth/logout"); }
        finally { token.value = null; user.value = null; }
    }

    return {token, user, loading, isAuthenticated, login, register, loadCurrentUser, logout};
});
