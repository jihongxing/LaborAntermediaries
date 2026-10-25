import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { useSession } from '../session/session'

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
})

client.interceptors.request.use(config => {
  const { token } = useSession()
  if (token.value) {
    config.headers.Authorization = `Bearer ${token.value}`
  }
  config.headers['X-Request-Id'] = crypto.randomUUID()
  return config
})

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
