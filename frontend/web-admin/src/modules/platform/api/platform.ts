/* eslint-disable max-lines */
import { request, requestBlob } from '../../../platform/http/client'
import type { Organization } from '../../onboarding/api/types'

export interface PlatformTask {
  taskId: string
  taskType: 'ORGANIZATION_REVIEW' | 'DEMAND_REVIEW' | 'APPEAL_REVIEW'
  reviewCaseId: string
  appealCaseId?: string
  resourceType: string
  resourceVersion: string
  status: string
  claimedByMe: boolean
  createdAt: string
}

export interface PlatformTaskView extends Omit<PlatformTask, 'resourceVersion' | 'claimedByMe'> {
  resourceId: string
  resourceVersion: number
}

export interface AppealSummary {
  appealCaseId: string
  originalDecisionId: string
  requestedBy: string
  requestReason: string
  originalDecision: string
  originalStatus: string
  originalReviewerId: string
  originalDecisionAt: string
}

export interface PlatformTaskDetails {
  task: PlatformTaskView
  history: ReviewHistory
  appeal?: AppealSummary
}

export type AppealOutcome = 'UPHOLD_ORIGINAL' | 'REQUEST_RECONSIDERATION'

export interface OrganizationReviewSubject {
  organizationId: string
  organizationRole: 'EMPLOYER' | 'LABOR_SUPPLIER'
  legalName: string
  registrationId: string
  authorizationReference?: string
  qualificationReference?: string
  status: string
  applicationVersion: number
  entityVersion: number
}

export interface DemandReviewSubject {
  demandId: string
  revision: number
  status: string
  title: string
  quantity: string
  quantityUnit: string
  startsAt: string
  endsAt: string
  bidDeadline: string
  serviceScope: string
  location: string
  relatedPartyRelation: string
  target: string
  pricingMode: string
  measurementBasis: string
  settlementCycle: string
  confirmationDeadline: string
  selectionRule: string
  reviewReason?: string
}

export type ReviewDecision = 'APPROVE' | 'NEEDS_CORRECTION' | 'REJECT'
export type VerificationDimension = 'COMPLETENESS' | 'AUTHENTICITY' | 'VALIDITY'
export type VerificationResult =
  | 'COMPLETE'
  | 'MISSING'
  | 'VERIFIED'
  | 'NOT_VERIFIED'
  | 'CONFLICT'
  | 'VALID'
  | 'EXPIRED'
  | 'NOT_APPLICABLE'

export interface PlatformEvidence {
  evidenceId: string
  resourceVersion: number
  category: string
  mediaType: string
  sizeBytes: number
  scanStatus: string
  scannedAt: string
  authenticityStatus: string
  sourceCode: string
}

export interface Verification {
  verificationId: string
  evidenceId: string
  resourceVersion: number
  dimension: VerificationDimension
  result: VerificationResult
  sourceLabel: string
  sourceReference?: string
  basis: string
  actorId: string
  createdAt: string
}

export interface ReviewHistory {
  reviewCaseId: string
  resourceType: string
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
    publicReason?: string
    decisionAt?: string
    correctionItems: Array<{
      correctionItemId: string
      itemCode: string
      title: string
      description: string
      applicantAction: string
      createdAt: string
    }>
  }>
  appeals: Array<{
    appealCaseId: string
    resourceVersion: number
    status: string
    outcome?: string
    reviewedAt?: string
  }>
  events: Array<{
    eventId: string
    action: string
    outcome: string
    resourceVersion?: number
    actorId?: string
    actorRole?: string
    reasonCode?: string
    occurredAt: string
  }>
  unresolvedItems: string[]
  internalView: boolean
}

export interface CorrectionItemRequest {
  itemCode: string
  title: string
  description: string
  applicantAction: string
}

export interface PlatformAuditEvent {
  eventId: string
  actorId?: string
  actorType: string
  organizationId?: string
  action: string
  resourceType: string
  resourceId: string
  outcome: string
  reviewCaseId?: string
  resourceVersion?: number
  actorRole?: string
  reasonCode?: string
  purposeCode?: string
  occurredAt: string
}

export interface PlatformAuditQueryResult {
  queryId: string
  events: PlatformAuditEvent[]
  nextCursor?: string
}

export interface PlatformAuditCoverageItem {
  eventCode: string
  moduleCode: string
  description: string
  status: 'NOT_IMPLEMENTED' | 'PENDING' | 'PARTIAL' | 'CONNECTED' | 'DEGRADED'
  lastEventId?: string
  lastEventAt?: string
  latencySeconds?: number
}

export type FindingStage = 'RESPONSE' | 'REVIEW'
export type FindingFactStatus = 'UNCONFIRMED' | 'SUPPORTED' | 'NOT_SUPPORTED'

export interface PlatformFindingSummary {
  findingId: string
  reviewCaseId: string
  resourceType: string
  resourceId: string
  resourceVersion?: number
  title: string
  factStatus: FindingFactStatus
  workflowStatus: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface PlatformFindingEntry {
  entryId: string
  entryType: string
  actorId: string
  content: string
  factStatus?: FindingFactStatus
  createdAt: string
}

export interface PlatformFindingClaim {
  claimId: string
  stage: FindingStage
  actorId: string
  status: string
  claimedAt: string
  closedAt?: string
  closeReason?: string
  active: boolean
}

export interface PlatformFindingDetail {
  summary: PlatformFindingSummary
  entries: PlatformFindingEntry[]
  activeClaim?: PlatformFindingClaim
}

export const platformApi = {
  queue(taskType: PlatformTask['taskType'] = 'ORGANIZATION_REVIEW') {
    return request<PlatformTask[]>({ method: 'GET', url: '/platform/tasks', params: { taskType } })
  },
  claim(taskId: string) {
    return request<{ taskId: string; claimId: string; status: string; active: boolean }>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/claim`,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
    })
  },
  release(taskId: string, reason: string) {
    return request<string>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/release`,
      data: { reason },
    })
  },
  recuse(taskId: string, reason: string) {
    return request<string>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/recusals`,
      data: { reason },
    })
  },
  subject(taskId: string) {
    return request<OrganizationReviewSubject>({
      method: 'GET',
      url: `/platform/tasks/${taskId}/organization`,
    })
  },
  demandSubject(taskId: string) {
    return request<DemandReviewSubject>({
      method: 'GET',
      url: `/platform/tasks/${taskId}/demand`,
    })
  },
  details(taskId: string) {
    return request<PlatformTaskDetails>({
      method: 'GET',
      url: `/platform/tasks/${taskId}`,
    })
  },
  reviewAppeal(taskId: string, outcome: AppealOutcome, reason: string) {
    return request<{ resultId: string; appealCaseId: string; outcome: AppealOutcome }>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/appeal-review`,
      data: { outcome, reason },
    })
  },
  decide(
    taskId: string,
    decision: ReviewDecision,
    reason?: string,
    correctionItems: CorrectionItemRequest[] = []
  ) {
    return request<Organization>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/organization/decision`,
      data: { decision, reason, correctionItems },
    })
  },
  decideDemand(
    taskId: string,
    decision: ReviewDecision,
    reason?: string,
    correctionItems: CorrectionItemRequest[] = []
  ) {
    return request<unknown>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/demand/decision`,
      data: { decision, reason, correctionItems },
    })
  },
  evidence(taskId: string) {
    return request<PlatformEvidence[]>({ method: 'GET', url: `/platform/tasks/${taskId}/evidence` })
  },
  preview(taskId: string, evidenceId: string) {
    return requestBlob({
      method: 'GET',
      url: `/platform/tasks/${taskId}/evidence/${evidenceId}/preview`,
      params: { page: 0 },
    })
  },
  verifications(taskId: string) {
    return request<Verification[]>({
      method: 'GET',
      url: `/platform/tasks/${taskId}/evidence/verifications`,
    })
  },
  recordVerification(
    taskId: string,
    evidenceId: string,
    input: {
      dimension: VerificationDimension
      result: VerificationResult
      sourceLabel: string
      sourceReference?: string
      basis: string
    }
  ) {
    return request<Verification>({
      method: 'POST',
      url: `/platform/tasks/${taskId}/evidence/${evidenceId}/verifications`,
      data: input,
    })
  },
  auditQuery(params: {
    organizationId?: string
    reviewCaseId?: string
    resourceId?: string
    resourceVersion?: number
    eventActorId?: string
    actorRole?: string
    action?: string
    outcome?: string
    fromAt?: string
    toAt?: string
    beforeId?: string
    limit?: number
    purposeCode?: string
  }) {
    return request<PlatformAuditQueryResult>({ method: 'GET', url: '/platform/audit', params })
  },
  auditCoverage() {
    return request<PlatformAuditCoverageItem[]>({ method: 'GET', url: '/platform/audit/coverage' })
  },
  auditHistory(reviewCaseId: string) {
    return request<ReviewHistory>({
      method: 'GET',
      url: `/platform/audit/requests/${reviewCaseId}`,
    })
  },
  findings(workflowStatus?: string) {
    return request<PlatformFindingSummary[]>({
      method: 'GET',
      url: '/platform/audit/findings',
      params: workflowStatus ? { workflowStatus } : undefined,
    })
  },
  finding(findingId: string) {
    return request<PlatformFindingDetail>({
      method: 'GET',
      url: `/platform/audit/findings/${findingId}`,
    })
  },
  createFinding(input: {
    reviewCaseId: string
    resourceVersion?: string
    eventId?: string
    evidenceId?: string
    title: string
    description: string
  }) {
    return request<PlatformFindingSummary>({
      method: 'POST',
      url: '/platform/audit/findings',
      data: input,
    })
  },
  claimFinding(findingId: string, stage: FindingStage) {
    return request<PlatformFindingClaim>({
      method: 'POST',
      url: `/platform/audit/findings/${findingId}/claim`,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      data: { stage },
    })
  },
  respondFinding(findingId: string, content: string) {
    return request<PlatformFindingDetail>({
      method: 'POST',
      url: `/platform/audit/findings/${findingId}/response`,
      data: { content },
    })
  },
  reviewFinding(
    findingId: string,
    factStatus: Exclude<FindingFactStatus, 'UNCONFIRMED'>,
    content: string
  ) {
    return request<PlatformFindingDetail>({
      method: 'POST',
      url: `/platform/audit/findings/${findingId}/review`,
      data: { factStatus, content },
    })
  },
}
