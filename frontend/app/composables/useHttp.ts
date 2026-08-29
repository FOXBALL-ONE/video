import {createFetch, type FetchOptions} from "ofetch";
import type {Ref} from "vue";
import type {ApiResult} from "~/types/http";

type ParamMode = "query" | "json";
type QueryParams = Record<string, unknown>;
type JsonBody = BodyInit | Record<string, unknown> | null | undefined;

export interface HttpRequestOptions<T> extends Omit<FetchOptions<"json">, "baseURL" | "query" | "params" | "body"> {
    method?: "GET" | "POST" | "PUT" | "DELETE" | "PATCH";
    payloadMode?: ParamMode;
    params?: QueryParams;
    body?: T;
    /** These statuses belong to business flows and should not trigger auth handling. */
    businessErrorStatuses?: number[];
}

const TOKEN_COOKIE = "chat_auth_token";
const LOGIN_PATH = "/login";
const UNAUTHORIZED_STATUS = 401;
const FORBIDDEN_STATUS = 403;

interface RequestFailure {
    status: number;
    message: string;
    data?: ApiResult<unknown>;
    transportFailure: boolean;
}

function normalizeAuthorization(token?: string | null): string {
    const value = token?.trim();
    if (!value) return "";
    return /^bearer\s+/i.test(value) ? value : `Bearer ${value}`;
}

function responseStatus(response: ApiResult<unknown>): number {
    const status = Number(response.status);
    return Number.isFinite(status) ? status : 500;
}

function responseMessage(response?: ApiResult<unknown>): string {
    return response?.message?.trim() || "请求失败";
}

function isSuccess(status: number): boolean {
    return status === 0 || (status >= 200 && status < 300);
}

function requestFailure(error: unknown): RequestFailure {
    const value = error as {
        response?: { status?: number; _data?: ApiResult<unknown> };
        data?: ApiResult<unknown>;
        statusCode?: number;
        message?: string;
    };
    const data = value.response?._data ?? value.data;
    const rawStatus = data?.status ?? value.response?.status ?? value.statusCode;
    const status = Number(rawStatus);
    return {
        status: Number.isFinite(status) && status > 0 ? status : 500,
        message: data?.message ?? value.message ?? "请求失败",
        data,
        transportFailure: rawStatus === undefined || rawStatus === null,
    };
}

function toRequestError(failure: RequestFailure) {
    return createError({
        statusCode: failure.status,
        statusMessage: failure.message,
        data: {
            ...(failure.data ?? {}),
            transport_failure: failure.transportFailure,
        },
    });
}

function isAbsoluteUrl(url: string): boolean {
    return /^https?:\/\//i.test(url);
}

function isBodyPayload(value: unknown): value is BodyInit {
    return typeof FormData !== "undefined" && value instanceof FormData
        || typeof Blob !== "undefined" && value instanceof Blob
        || typeof URLSearchParams !== "undefined" && value instanceof URLSearchParams
        || typeof ArrayBuffer !== "undefined" && value instanceof ArrayBuffer;
}

function persistAccessToken(data: unknown, authToken: Ref<string | null>) {
    if (!data || typeof data !== "object" || Array.isArray(data)) return;
    const accessToken = (data as { access_token?: unknown }).access_token;
    if (typeof accessToken === "string" && accessToken.trim()) {
        authToken.value = accessToken.trim();
    }
}

export const useHttp = (baseURL?: string) => {
    const authToken = useCookie<string | null>(TOKEN_COOKIE, {
        maxAge: 60 * 60 * 24 * 7,
        sameSite: "lax",
        path: "/",
    });
    const router = useRouter();
    const runtimeConfig = useRuntimeConfig();
    const apiBase = baseURL || runtimeConfig.public.apiBase || "http://127.0.0.1:8080/api";
    const http = createFetch({
        defaults: {
            baseURL: apiBase,
            credentials: "include",
            headers: {Accept: "application/json"},
        },
    });

    let sessionCleanupPromise: Promise<void> | null = null;

    const clearSession = async () => {
        authToken.value = null;
        if (import.meta.server || router.currentRoute.value.path === LOGIN_PATH) return;
        if (!sessionCleanupPromise) {
            sessionCleanupPromise = router.replace({
                path: LOGIN_PATH,
                query: {redirect: router.currentRoute.value.fullPath},
            }).then(() => undefined).catch(() => undefined).finally(() => {
                sessionCleanupPromise = null;
            });
        }
        await sessionCleanupPromise;
    };

    const requestBase = async <TResponse, TPayload = Record<string, unknown>>(
        url: string,
        payload?: TPayload,
        options: HttpRequestOptions<TPayload> = {},
    ): Promise<ApiResult<TResponse>> => {
        const {
            payloadMode = "query",
            method = "GET",
            params,
            body,
            businessErrorStatuses = [],
            headers,
            ...fetchOptions
        } = options;
        const rawPayload = body ?? payload;
        const bodyPayload = payloadMode === "json" || isBodyPayload(rawPayload);
        const query = bodyPayload
            ? params
            : (params ?? (payload as QueryParams | undefined));
        const requestBody = bodyPayload ? (rawPayload as JsonBody) : undefined;
        const requestHeaders = new Headers(headers as HeadersInit | undefined);
        requestHeaders.set("Accept", "application/json");
        const authorization = normalizeAuthorization(authToken.value);
        if (authorization && !requestHeaders.has("Authorization")) {
            requestHeaders.set("Authorization", authorization);
        }

        try {
            const response = await http<ApiResult<TResponse>>(isAbsoluteUrl(url) ? url : url, {
                method,
                ...fetchOptions,
                query,
                headers: requestHeaders,
                body: requestBody,
            });
            const status = responseStatus(response);
            if (!isSuccess(status)) {
                throw createError({statusCode: status, statusMessage: responseMessage(response), data: response});
            }
            persistAccessToken(response.data, authToken);
            return response;
        } catch (error: unknown) {
            const failure = requestFailure(error);
            if (failure.status === UNAUTHORIZED_STATUS && !businessErrorStatuses.includes(failure.status)) {
                await clearSession();
            }
            if (failure.status === FORBIDDEN_STATUS && !businessErrorStatuses.includes(failure.status)) {
                // Keep the backend message intact; callers can show it in their own UI.
            }
            throw toRequestError(failure);
        }
    };

    const request = async <TResponse, TPayload = Record<string, unknown>>(
        url: string,
        payload?: TPayload,
        options: HttpRequestOptions<TPayload> = {},
    ): Promise<TResponse> => (await requestBase<TResponse, TPayload>(url, payload, options)).data;

    return {
        request,
        requestRaw: requestBase,
        get: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            request<TResponse>(url, params, {...options, method: "GET", payloadMode: "query"}),
        getRaw: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            requestBase<TResponse>(url, params, {...options, method: "GET", payloadMode: "query"}),
        post: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            request<TResponse, TPayload>(url, payload, {...options, method: "POST"}),
        postRaw: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            requestBase<TResponse, TPayload>(url, payload, {...options, method: "POST"}),
        put: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            request<TResponse, TPayload>(url, payload, {...options, method: "PUT"}),
        putRaw: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            requestBase<TResponse, TPayload>(url, payload, {...options, method: "PUT"}),
        patch: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            request<TResponse, TPayload>(url, payload, {...options, method: "PATCH"}),
        patchRaw: <TResponse, TPayload = QueryParams>(url: string, payload?: TPayload, options?: Omit<HttpRequestOptions<TPayload>, "method">) =>
            requestBase<TResponse, TPayload>(url, payload, {...options, method: "PATCH"}),
        delete: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            request<TResponse>(url, params, {...options, method: "DELETE", payloadMode: "query"}),
        deleteRaw: <TResponse>(url: string, params?: QueryParams, options?: Omit<HttpRequestOptions<QueryParams>, "method" | "payloadMode" | "params" | "body">) =>
            requestBase<TResponse>(url, params, {...options, method: "DELETE", payloadMode: "query"}),
    };
};
