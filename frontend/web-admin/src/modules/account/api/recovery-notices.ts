import { request } from '../../../platform/http/client'

/** 异常解除认领后，仅向原认领人展示的最小站内通知。 */
export interface RecoveryNotice {
  noticeId: string
  taskId: string
  taskType: 'ORGANIZATION_REVIEW' | 'DEMAND_REVIEW' | 'APPEAL_REVIEW'
  claimedAt: string
  recoveredAt: string
}

/** 本人通知接口不要求仍持有平台岗位，服务端按登录账号过滤。 */
export const recoveryNoticesApi = {
  list() {
    return request<RecoveryNotice[]>({ method: 'GET', url: '/platform/tasks/recovery-notices' })
  },
}
