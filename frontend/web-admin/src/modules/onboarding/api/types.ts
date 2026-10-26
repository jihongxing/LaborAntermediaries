export type OrganizationRole = 'EMPLOYER' | 'LABOR_SUPPLIER'
export type OrganizationStatus =
  'DRAFT' | 'PENDING_REVIEW' | 'APPROVED' | 'NEEDS_CORRECTION' | 'REJECTED' | 'SUSPENDED'
export type MembershipRole =
  | 'ORGANIZATION_ADMIN'
  | 'DEMAND_EDITOR'
  | 'DEMAND_PUBLISHER'
  | 'SUPPLIER_BIDDER'
  | 'DEMAND_SELECTOR'
export type DemandStatus =
  | 'DRAFT'
  | 'PENDING_REVIEW'
  | 'NEEDS_CORRECTION'
  | 'REJECTED'
  | 'APPROVED_PENDING_PUBLISH'
  | 'OPEN_FOR_BID'
  | 'SUSPENDED'
  | 'CLOSED'

export interface Organization {
  id: string
  organizationRole: OrganizationRole
  legalName: string
  registrationId: string
  authorizationReference: string
  qualificationReference?: string
  status: OrganizationStatus
  version: string
  applicationVersion: string
  createdBy: string
  reviewedBy?: string
  reviewReason?: string
}

export interface OrganizationCorrectionItem {
  correctionItemId: string
  itemCode: string
  title: string
  description: string
  applicantAction: string
  createdAt: string
}

export interface ApplicantReviewHistory {
  reviewCaseId: string
  resourceType: 'ORGANIZATION' | 'DEMAND'
  resourceId: string
  currentStatus: string
  currentVersion: number
  historyCoverage: string
  versions: Array<{
    resourceVersion: number
    createdAt: string
    submittedAt?: string
    decisionId?: string
    decision?: string
    decisionAt?: string
    publicReason?: string
    correctionItems: OrganizationCorrectionItem[]
  }>
  appeals: Array<{
    appealCaseId: string
    resourceVersion: number
    status: 'PENDING' | 'IN_REVIEW' | 'REVIEWED' | 'SUPERSEDED'
    outcome?: 'UPHOLD_ORIGINAL' | 'REQUEST_RECONSIDERATION'
    reviewedAt?: string
  }>
  unresolvedItems: string[]
}

export type OrganizationReviewHistory = ApplicantReviewHistory & { resourceType: 'ORGANIZATION' }

export interface MembershipRequest {
  requestId: string
  actorId: string
  requestedRole: MembershipRole
  createdAt: string
}

export interface Demand {
  id: string
  organizationId: string
  revision: string
  status: DemandStatus
  title: string
  quantity: string
  quantityUnit: string
  startsAt: string
  endsAt: string
  bidDeadline: string
  confirmationDeadline: string
  serviceScope: string
  location: string
  relatedPartyRelation?: string
  target: string
  pricingMode: string
  measurementBasis: string
  settlementCycle: string
  selectionRule: string
  reviewReason?: string
}

export interface OrganizationForm {
  organizationRole: OrganizationRole
  legalName: string
  registrationId: string
  authorizationReference: string
  qualificationReference?: string
}

export interface DemandForm {
  relatedPartyRelation: string
  serviceScope: string
  location: string
  startsAt: string
  endsAt: string
  title: string
  quantity: string
  quantityUnit: string
  target: string
  pricingMode: string
  measurementBasis: string
  settlementCycle: string
  bidDeadline: string
  confirmationDeadline: string
  selectionRule: string
}

export type BidStatus = 'ACTIVE' | 'WITHDRAWN' | 'CLOSED'
export type AwardStatus = 'PENDING_CONFIRMATION' | 'CONFIRMED' | 'REJECTED' | 'EXPIRED'

export interface Bid {
  id: string
  demandId: string
  supplierOrganizationId: string
  revision: string
  status: BidStatus
  committedQuantity: string
  commitment: string
  deviationItems: string
  proposal: string
  quotedAmount: string
  quoteCurrency: string
  quoteUnit: string
  taxRate: string
  paymentTerm: string
  paymentArrangement: string
  createdAt: string
}

export interface BidForm {
  committedQuantity: string
  commitment: string
  deviationItems: string
  proposal: string
  quotedAmount: string
  quoteCurrency: string
  quoteUnit: string
  taxRate: string
  paymentTerm: string
  paymentArrangement: string
  acceptedTerms: boolean
}

export interface Award {
  id: string
  demandId: string
  bidId: string
  supplierOrganizationId: string
  bidRevision: string
  awardedQuantity: string
  confirmationDeadline: string
  status: AwardStatus
  decidedAt: string
  confirmedAt?: string
  terms: ConfirmationTerms
}

export interface ConfirmationTerms {
  demandRevision: string
  demandTitle: string
  serviceScope: string
  location: string
  startsAt: string
  endsAt: string
  demandQuantity: string
  quantityUnit: string
  target: string
  pricingMode: string
  measurementBasis: string
  settlementCycle: string
  bidDeadline: string
  confirmationDeadline: string
  selectionRule: string
  committedQuantity: string
  commitment: string
  deviationItems: string
  proposal: string
  quotedAmount: string
  quoteCurrency: string
  quoteUnit: string
  taxRate: string
  paymentTerm: string
  paymentArrangement: string
}

export interface Snapshot {
  id: string
  awardId: string
  demandId: string
  employerOrganizationId: string
  supplierOrganizationId: string
  bidId: string
  bidRevision: string
  awardedQuantity: string
  status: string
  confirmedBy: string
  confirmedAt: string
  snapshotPayload: string
}
