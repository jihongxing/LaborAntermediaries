import { request } from '../../../platform/http/client'
import type { Award, Bid, BidForm, Snapshot } from './types'

export const biddingApi = {
  submit(demandId: string, form: BidForm) {
    return request<Bid>({
      method: 'POST',
      url: `/bidding/demands/${demandId}/bids`,
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      data: form,
    })
  },
  ownBids() {
    return request<Bid[]>({ method: 'GET', url: '/bidding/bids/mine' })
  },
  withdraw(bidId: string) {
    return request<Bid>({ method: 'POST', url: `/bidding/bids/${bidId}/withdraw` })
  },
  demandBids(demandId: string) {
    return request<Bid[]>({ method: 'GET', url: `/bidding/demands/${demandId}/bids` })
  },
  award(demandId: string, allocations: Array<{ bidId: string; quantity: string }>) {
    return request<Award[]>({
      method: 'POST',
      url: `/bidding/demands/${demandId}/awards`,
      data: {
        allocations,
        allocationMode: 'ABSOLUTE',
        roundingMode: 'NONE',
      },
    })
  },
  awards(demandId: string) {
    return request<Award[]>({ method: 'GET', url: `/bidding/demands/${demandId}/awards` })
  },
  confirm(awardId: string, accepted: boolean, reason?: string) {
    return request<Award>({
      method: 'POST',
      url: `/bidding/awards/${awardId}/confirmation`,
      data: { accepted, reason },
    })
  },
  snapshots(demandId: string) {
    return request<Snapshot[]>({ method: 'GET', url: `/bidding/demands/${demandId}/snapshots` })
  },
}
