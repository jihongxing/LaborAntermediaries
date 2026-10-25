import type { DemandForm, OrganizationForm } from '../api/types'
import { toLocalDateTime } from '../../../shared/lib/format'

export function emptyOrganizationForm(): OrganizationForm {
  return {
    organizationRole: 'EMPLOYER',
    legalName: '',
    registrationId: '',
    authorizationReference: '',
    qualificationReference: '',
  }
}

export function emptyDemandForm(): DemandForm {
  const start = new Date(Date.now() + 86400000)
  const end = new Date(Date.now() + 31 * 86400000)
  const deadline = new Date(Date.now() + 12 * 3600000)
  const confirmationDeadline = new Date(Date.now() + 2 * 86400000)
  return {
    relatedPartyRelation: '',
    serviceScope: '',
    location: '',
    startsAt: toLocalDateTime(start.toISOString()),
    endsAt: toLocalDateTime(end.toISOString()),
    title: '',
    quantity: '1',
    quantityUnit: '人天',
    target: '',
    pricingMode: '',
    measurementBasis: '',
    settlementCycle: '',
    bidDeadline: toLocalDateTime(deadline.toISOString()),
    confirmationDeadline: toLocalDateTime(confirmationDeadline.toISOString()),
    selectionRule: '',
  }
}
