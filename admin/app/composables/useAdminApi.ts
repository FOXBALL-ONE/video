import type { UserRecord, UserListData } from '~/types/users'

interface ApiEnvelope<T> {
  status: number
  message: string
  data: T
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  query?: Record<string, string | number | boolean | string[] | undefined>
}

export interface UserPayload {
  username: string
  email: string
  password?: string
  role_codes?: string[]
  enabled: boolean
}

export function useAdminApi() {
  const config = useRuntimeConfig()
  const token = useCookie<string | null>('chat_auth_token', {
    maxAge: 60 * 60 * 24 * 7,
    sameSite: 'lax',
    path: '/',
  })

  async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    try {
      const response = await $fetch<ApiEnvelope<T>>(path, {
        baseURL: config.public.apiBase,
        method: options.method ?? 'GET',
        query: options.query,
        headers: token.value ? { Authorization: `Bearer ${token.value}` } : undefined,
        credentials: 'include',
      })
      if (response.status < 200 || response.status >= 300) {
        throw new Error(response.message || '请求失败')
      }
      return response.data
    } catch (error: unknown) {
      const failure = error as { response?: { status?: number; _data?: ApiEnvelope<unknown> }; message?: string }
      const status = failure.response?._data?.status ?? failure.response?.status
      const message = failure.response?._data?.message ?? failure.message ?? '请求失败'
      if (status === 401) {
        token.value = null
        await navigateTo('/login')
      }
      throw new Error(message)
    }
  }

  return {
    token,
    login: (username: string, password: string) => request<{ access_token: string }>('/auth/login', {
      method: 'POST',
      query: { username, password },
    }),
    listUsers: (query: { keyword?: string; enabled?: boolean; role_code?: string; page: number; size: number }) =>
      request<UserListData>('/admin/users', { query }),
    getUser: (id: number) => request<UserRecord>(`/admin/users/${id}`),
    createUser: (payload: UserPayload) => request<{ user_id: number }>('/admin/users', {
      method: 'POST',
      query: payload,
    }),
    updateUser: (id: number, payload: UserPayload) => request<{ user_id: number }>(`/admin/users/${id}`, {
      method: 'PUT',
      query: payload,
    }),
    setUserStatus: (id: number, enabled: boolean) => request<{ user_id: number; enabled: boolean }>(`/admin/users/${id}/status`, {
      method: 'PATCH',
      query: { enabled },
    }),
  }
}
