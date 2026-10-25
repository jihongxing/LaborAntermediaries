import axios from 'axios'
import { ref } from 'vue'

/** 当前标签页内存中的访问令牌，不写入 localStorage、sessionStorage 或 URL。 */
const token = ref('')
/** 仅作为界面状态的当前组织编号，服务端始终从签名 JWT 与成员关系授权。 */
const organizationId = ref('')
/** 合并多个同时发生的 401 刷新请求，避免同一旧 Refresh Token 被并发轮换。 */
let renewal: Promise<boolean> | undefined

/**
 * 获取与服务端 CSRF Cookie 配对的一次性双提交值。
 * @return 需要放入 X-CSRF-Token 头的短时 token
 */
export async function fetchCsrfToken(): Promise<string> {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  const response = await axios.get(`${baseURL}/auth/csrf`, { withCredentials: true })
  const csrf = response.data?.data?.token
  if (typeof csrf !== 'string' || !csrf) {
    throw new Error('无法建立安全会话，请刷新页面后重试')
  }
  return csrf
}

/**
 * 发布一个短时 Access Token 到当前标签页内存。
 * @param value 认证接口返回的访问令牌
 */
export function setToken(value: string) {
  token.value = value.trim()
}

/**
 * 记录组织编号以便界面显示；此值不作为 API 授权依据。
 * @param value 已由服务端上下文切换验证的组织编号
 */
export function setOrganizationId(value: string) {
  organizationId.value = value.trim()
}

/** 清除当前标签页的访问令牌和选择组织显示状态。 */
export function clearSession() {
  token.value = ''
  organizationId.value = ''
}

/**
 * 使用 HttpOnly Refresh Cookie 恢复或轮换内存 Access Token；并发调用共享同一个刷新事务。
 * @return Cookie 会话仍有效且取得新令牌时返回 true
 */
export function renewSession(): Promise<boolean> {
  if (renewal) {
    return renewal
  }
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  renewal = (async () => {
    try {
      const csrf = await fetchCsrfToken()
      const response = await axios.post(
        `${baseURL}/auth/token/refresh`,
        {},
        {
          withCredentials: true,
          headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
        }
      )
      const accessToken = response.data?.data?.accessToken
      if (response.data?.code !== 200 || typeof accessToken !== 'string' || !accessToken) {
        clearSession()
        return false
      }
      setToken(accessToken)
      return true
    } catch {
      clearSession()
      return false
    }
  })().finally(() => {
    renewal = undefined
  })
  return renewal
}

/**
 * 以当前 HttpOnly Cookie 执行 CSRF 保护登出，随后无条件清除本地会话状态。
 */
export async function logoutSession(): Promise<void> {
  const baseURL = import.meta.env.VITE_API_BASE_URL || '/api'
  try {
    const csrf = await fetchCsrfToken()
    await axios.post(
      `${baseURL}/auth/logout`,
      {},
      {
        withCredentials: true,
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      }
    )
  } finally {
    clearSession()
  }
}

/**
 * 提供当前 Vue 应用需要的响应式认证状态与更新操作。
 * @return 当前标签页的内存认证状态
 */
export function useSession() {
  return { token, organizationId, setToken, setOrganizationId, clear: clearSession }
}
