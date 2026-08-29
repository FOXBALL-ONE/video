import {storeToRefs} from "pinia";
import {useAuthStore} from "~/stores/auth";
import type {AuthUser} from "~/types/auth";

export type {AuthUser} from "~/types/auth";

/** 兼容现有页面的认证入口，实际状态由 Pinia auth store 管理。 */
export const useAuth = () => {
    const store = useAuthStore();
    const refs = storeToRefs(store);
    return {...refs, login: store.login, register: store.register, loadCurrentUser: store.loadCurrentUser, logout: store.logout};
};
