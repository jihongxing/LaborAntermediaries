import { request } from '../../../platform/http/client'
import type { MembershipRequest, MembershipRole, Organization, OrganizationForm } from './types'

export const identityApi = {
  createOrganization(form: OrganizationForm) {
    return request<Organization>({ method: 'POST', url: '/organizations', data: form })
  },
  getOrganization(id: string) {
    return request<Organization>({ method: 'GET', url: `/organizations/${id}` })
  },
  updateOrganization(id: string, form: OrganizationForm & { expectedVersion: string }) {
    const {
      expectedVersion,
      legalName,
      registrationId,
      authorizationReference,
      qualificationReference,
    } = form
    return request<Organization>({
      method: 'PUT',
      url: `/organizations/${id}`,
      data: {
        expectedVersion,
        legalName,
        registrationId,
        authorizationReference,
        qualificationReference,
      },
    })
  },
  submitOrganization(id: string) {
    return request<Organization>({ method: 'POST', url: `/organizations/${id}/submit` })
  },
  pendingOrganizations() {
    return request<Organization[]>({ method: 'GET', url: '/organizations/platform/pending' })
  },
  reviewOrganization(id: string, decision: string, reason?: string) {
    return request<Organization>({
      method: 'POST',
      url: `/organizations/platform/${id}/review`,
      data: { decision, reason },
    })
  },
  joinOrganization(id: string, requestedRole: MembershipRole) {
    return request<string>({
      method: 'POST',
      url: `/organizations/${id}/join`,
      data: { requestedRole },
    })
  },
  pendingMemberships(id: string) {
    return request<MembershipRequest[]>({
      method: 'GET',
      url: `/organizations/${id}/membership-requests`,
    })
  },
  decideMembership(id: string, requestId: string, decision: string, reason?: string) {
    return request({
      method: 'POST',
      url: `/organizations/${id}/membership-requests/${requestId}/decision`,
      data: { decision, reason },
    })
  },
}
