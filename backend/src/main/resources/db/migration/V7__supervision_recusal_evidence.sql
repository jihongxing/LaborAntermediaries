-- Keep platform supervision attempts append-only and distinguish recusals from completed checks.
UPDATE business_bid_award a
SET status = 'EXPIRED', decision_reason = 'LEGACY_UNBOUND_BID'
WHERE a.status = 'PENDING_CONFIRMATION'
  AND EXISTS (
      SELECT 1 FROM business_bid b
      WHERE b.id = a.bid_id AND b.demand_revision IS NULL
  );

UPDATE business_bid
SET status = 'CLOSED', updated_at = clock_timestamp()
WHERE demand_revision IS NULL AND status <> 'CLOSED';

ALTER TABLE business_bid_supervision_event
    ADD COLUMN outcome varchar(16) NOT NULL DEFAULT 'RECORDED'
    CHECK (outcome IN ('RECORDED', 'RECUSAL'));

CREATE TRIGGER business_bid_supervision_event_append_only
    BEFORE UPDATE OR DELETE ON business_bid_supervision_event
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
