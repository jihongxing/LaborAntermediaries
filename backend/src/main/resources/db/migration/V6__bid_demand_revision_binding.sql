-- Bind each bid to the exact demand revision it answered; legacy bids without a safe source revision fail closed.
ALTER TABLE business_bid ADD COLUMN demand_revision bigint;
ALTER TABLE business_bid
    ADD CONSTRAINT business_bid_demand_revision_fk
    FOREIGN KEY (demand_id, demand_revision)
    REFERENCES business_demand_revision (demand_id, revision);

CREATE TRIGGER business_bid_demand_revision_immutable
    BEFORE UPDATE OF demand_revision ON business_bid
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
