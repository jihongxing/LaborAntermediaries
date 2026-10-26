import { request } from '../../../platform/http/client'
import type { ApplicantReviewHistory, Demand, DemandForm } from './types'

export const demandApi = {
  create(organizationId: string, form: DemandForm) {
    return request<Demand>({
      method: 'POST',
      url: `/demands/organizations/${organizationId}`,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      data: toPayload(form),
    })
  },
  list(organizationId: string) {
    return request<Demand[]>({ method: 'GET', url: `/demands/organizations/${organizationId}` })
  },
  reviewHistory(id: string) {
    return request<ApplicantReviewHistory>({ method: 'GET', url: `/demands/${id}/review-history` })
  },
  update(demand: Demand, form: DemandForm) {
    return request<Demand>({
      method: 'PUT',
      url: `/demands/${demand.id}/revisions/${demand.revision}`,
      data: toPayload(form),
    })
  },
  submit(id: string) {
    return request<Demand>({ method: 'POST', url: `/demands/${id}/submit` })
  },
  publish(id: string) {
    return request<Demand>({ method: 'POST', url: `/demands/${id}/publish` })
  },
  pendingReviews() {
    return request<Demand[]>({ method: 'GET', url: '/demands/platform/pending' })
  },
  review(id: string, decision: string, reason?: string) {
    return request<Demand>({
      method: 'POST',
      url: `/demands/platform/${id}/review`,
      data: { decision, reason },
    })
  },
  openForBid() {
    return request<Demand[]>({ method: 'GET', url: '/demands/open-for-bid' })
  },
}

function toPayload(form: DemandForm) {
  return {
    ...form,
    quantity: form.quantity,
    startsAt: new Date(form.startsAt).toISOString(),
    endsAt: new Date(form.endsAt).toISOString(),
    bidDeadline: new Date(form.bidDeadline).toISOString(),
    confirmationDeadline: new Date(form.confirmationDeadline).toISOString(),
  }
}
