import type { DemandStatus, OrganizationStatus } from '../../modules/onboarding/api/types'

export const organizationStatusLabel: Record<OrganizationStatus, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待平台审核',
  APPROVED: '已准入',
  NEEDS_CORRECTION: '待补正',
  REJECTED: '已拒绝',
  SUSPENDED: '已暂停',
}

export const demandStatusLabel: Record<DemandStatus, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待平台审核',
  NEEDS_CORRECTION: '待补正',
  REJECTED: '已拒绝',
  APPROVED_PENDING_PUBLISH: '待发布',
  OPEN_FOR_BID: '开放应标',
  SUSPENDED: '已暂停',
  CLOSED: '已结束',
}

export function statusType(status: string) {
  if (['APPROVED', 'OPEN_FOR_BID'].includes(status)) {
    return 'success'
  }
  if (['NEEDS_CORRECTION', 'REJECTED', 'SUSPENDED'].includes(status)) {
    return 'danger'
  }
  if (['PENDING_REVIEW', 'APPROVED_PENDING_PUBLISH'].includes(status)) {
    return 'warning'
  }
  return 'info'
}

export function displayDate(value?: string) {
  if (!value) {
    return '未设置'
  }
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(value)
  )
}

export function toLocalDateTime(value?: string) {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}
