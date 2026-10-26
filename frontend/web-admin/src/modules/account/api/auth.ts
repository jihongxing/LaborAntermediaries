import { request } from '../../../platform/http/client'
import { fetchCsrfToken } from '../../../platform/session/session'

/** 已验证账号对外可见的安全状态摘要。 */
export interface AccountSummary {
  accountId: string
  status: 'ACTIVE' | 'LOCKED' | 'DISABLED'
  displayName?: string
}

/** 本地认证 API 返回的短时 Access Token；Refresh Token 只由 HttpOnly Cookie 承载。 */
export interface SessionResponse {
  accessToken: string
  accessExpiresAt: string
  account: AccountSummary | null
}

/** 短信挑战的匿名响应，不含手机号或验证码。 */
export interface ChallengeResponse {
  challengeId: string
  expiresAt: string
  resendAfter: string
  message: string
}

/** 账号可见的组织审核和成员授权进度。 */
export interface OrganizationEntry {
  organizationId: string
  organizationRole: 'EMPLOYER' | 'LABOR_SUPPLIER'
  organizationStatus:
    'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'NEEDS_CORRECTION' | 'REJECTED' | 'SUSPENDED'
  membershipStatus?: 'ACTIVE' | 'REVOKED'
  memberRole?:
    | 'ORGANIZATION_ADMIN'
    | 'DEMAND_EDITOR'
    | 'DEMAND_PUBLISHER'
    | 'SUPPLIER_BIDDER'
    | 'DEMAND_SELECTOR'
  membershipRequestStatus?: 'PENDING' | 'APPROVED' | 'REJECTED'
  requestedRole?: string
}

/** Mock SMS 在 local/test 回环环境中临时可查看的验证码。 */
export interface MockSmsMessage {
  maskedPhone: string
  code: string
  expiresAt: string
}

/** 当前用到的手机号认证、找回和组织上下文接口。 */
export const authApi = {
  /**
   * 开始注册、短信登录或密码找回验证码挑战。
   * @param phone 手机号
   * @param purpose 服务端认证流程标识
   * @return 匿名挑战编号和时间限制
   */
  startChallenge(phone: string, purpose: 'register' | 'login' | 'password-reset') {
    const endpoint =
      purpose === 'register'
        ? '/auth/register/start'
        : purpose === 'login'
          ? '/auth/login/sms/start'
          : '/auth/password-reset/start'
    return request<ChallengeResponse>({ method: 'POST', url: endpoint, data: { phone } })
  },

  /**
   * 完成手机号注册并接受协议版本记录。
   * @param input 用户提交的手机号、挑战和初始密码
   * @return 已建立的个人账号会话
   */
  register(input: {
    phone: string
    challengeId: string
    code: string
    password: string
    displayName?: string
    termsVersion: string
    privacyVersion: string
  }) {
    return request<SessionResponse>({ method: 'POST', url: '/auth/register/verify', data: input })
  },

  /**
   * 使用短信验证码完成登录。
   * @param challengeId 登录短信挑战编号
   * @param code 用户收到的验证码
   * @return 已建立的个人账号会话
   */
  loginBySms(challengeId: string, code: string) {
    return request<SessionResponse>({
      method: 'POST',
      url: '/auth/login/sms/verify',
      data: { challengeId, code },
    })
  },

  /**
   * 使用手机号和备用密码完成登录。
   * @param phone 已验证手机号
   * @param password 注册时设置的备用密码
   * @return 已建立的个人账号会话
   */
  loginByPassword(phone: string, password: string) {
    return request<SessionResponse>({
      method: 'POST',
      url: '/auth/login/password',
      data: { phone, password },
    })
  },

  /**
   * 使用密码找回验证码签发一次性密码重置票据。
   * @param challengeId 找回短信挑战编号
   * @param code 用户收到的验证码
   * @return 一次性重置凭据及失效时间
   */
  verifyPasswordReset(challengeId: string, code: string) {
    return request<{ ticketId: string; resetToken: string; expiresAt: string }>({
      method: 'POST',
      url: '/auth/password-reset/verify',
      data: { challengeId, code },
    })
  },

  /**
   * 提交一次性凭据完成密码重置。
   * @param input 一次性重置票据和新密码
   * @return 空成功响应
   */
  completePasswordReset(input: { ticketId: string; resetToken: string; newPassword: string }) {
    return request<void>({ method: 'POST', url: '/auth/password-reset/complete', data: input })
  },

  /**
   * 读取当前已登录账号的安全状态和显示名。
   * @return 当前账号摘要
   */
  me() {
    return request<AccountSummary>({ method: 'GET', url: '/auth/me' })
  },

  /**
   * 读取当前账号参与或申请加入组织的审批进度。
   * @return 已审核、待审核及待加入组织状态
   */
  organizations() {
    return request<OrganizationEntry[]>({ method: 'GET', url: '/auth/organizations' })
  },

  /**
   * 创建新的 CSRF 令牌并选择经服务端确认的组织上下文。
   * @param organizationId 当前账号的已审核活动成员组织
   * @return 绑定新组织权限快照的 Access Token
   */
  async selectOrganization(organizationId: string) {
    const csrf = await fetchCsrfToken()
    return request<SessionResponse>({
      method: 'POST',
      url: '/auth/context',
      data: { organizationId },
      headers: { 'X-CSRF-Token': csrf },
    })
  },

  /**
   * Switches the current browser session into the server-authorized platform role.
   * @return a short-lived platform-context access token
   */
  async selectPlatformContext() {
    const csrf = await fetchCsrfToken()
    return request<SessionResponse>({
      method: 'POST',
      url: '/auth/platform-context',
      data: {},
      headers: { 'X-CSRF-Token': csrf },
    })
  },

  /**
   * 查询 local/test 回环环境中的 Mock SMS 收件箱。
   * @param challengeId 服务端返回的挑战编号
   * @return 合成验证码，仅 local/test 前端展示
   */
  mockSms(challengeId: string) {
    return request<MockSmsMessage>({ method: 'GET', url: `/auth/dev/mock-sms/${challengeId}` })
  },
}
