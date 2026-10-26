import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { clearSession, renewSession, useSession } from '../session/session'

export interface ApiEnvelope<T> {
  code: number
  message: string
  errorKey?: string
  requestId?: string
  data: T
}

export class ApiError extends Error {
  readonly code?: number
  readonly requestId?: string

  constructor(message: string, code?: number, requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.requestId = requestId
  }
}

const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,
})

let unauthorizedHandler: (() => void) | undefined

/**
 * 注册受保护路由的登录过期处理器。
 * @param handler 会话无法刷新后的路由动作
 */
export function onSessionExpired(handler: () => void) {
  unauthorizedHandler = handler
}

type RetriableRequest = InternalAxiosRequestConfig & { authRetry?: boolean }

client.interceptors.request.use(config => {
  const { token } = useSession()
  if (token.value) {
    config.headers.Authorization = `Bearer ${token.value}`
  }
  config.headers['X-Request-Id'] = crypto.randomUUID()
  return config
})

client.interceptors.response.use(
  response => response,
  async (error: AxiosError<ApiEnvelope<unknown>>) => {
    const config = error.config as RetriableRequest | undefined
    const path = config?.url || ''
    if (error.response?.status !== 401 || !config || config.authRetry || path.includes('/auth/')) {
      return Promise.reject(error)
    }
    config.authRetry = true
    if (!(await renewSession())) {
      clearSession()
      unauthorizedHandler?.()
      return Promise.reject(error)
    }
    config.headers.Authorization = `Bearer ${useSession().token.value}`
    return client.request(config)
  }
)

export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  try {
    const response = await client.request<ApiEnvelope<T>>(config)
    const envelope = response.data
    if (envelope.code !== 200) {
      throw new ApiError(envelope.message, envelope.code, envelope.requestId)
    }
    return envelope.data
  } catch (error) {
    if (error instanceof ApiError) {
      throw error
    }
    const axiosError = error as AxiosError<ApiEnvelope<unknown>>
    const payload = axiosError.response?.data
    throw new ApiError(
      payload?.message || axiosError.message || '请求失败，请稍后重试',
      payload?.code,
      payload?.requestId
    )
  }
}

/**
 * Reads an explicitly authorized non-JSON response through the same authenticated HTTP client.
 * @param config request settings for a scoped binary endpoint
 * @return the response body as a browser Blob
 */
export async function requestBlob(config: AxiosRequestConfig): Promise<Blob> {
  try {
    const response = await client.request<Blob>({ ...config, responseType: 'blob' })
    return response.data
  } catch (error) {
    const axiosError = error as AxiosError<ApiEnvelope<unknown>>
    const payload = axiosError.response?.data
    if (payload instanceof Blob) {
      try {
        const parsed = JSON.parse(await payload.text()) as Partial<ApiEnvelope<unknown>>
        throw new ApiError(parsed.message || '请求失败，请稍后重试', parsed.code, parsed.requestId)
      } catch (parseError) {
        if (parseError instanceof ApiError) {throw parseError}
      }
    }
    throw new ApiError(
      axiosError.message || '受控材料预览暂时无法读取',
      undefined,
      undefined
    )
  }
}
