-- 确认窗口由获批需求版本明确给出；历史需求保留 NULL 并在选标前失败关闭。
ALTER TABLE business_demand_revision
    ADD COLUMN confirmation_deadline timestamptz;
ALTER TABLE business_demand_revision
    ADD CONSTRAINT business_demand_revision_confirmation_deadline_check
    CHECK (confirmation_deadline IS NULL OR confirmation_deadline > bid_deadline);
ALTER TABLE business_demand_delivery ADD COLUMN confirmation_deadline timestamptz;
ALTER TABLE business_demand_delivery
    ADD CONSTRAINT business_demand_delivery_confirmation_deadline_check
    CHECK (confirmation_deadline IS NULL OR confirmation_deadline > bid_deadline);

ALTER TABLE business_bid_award ADD COLUMN decision_reason varchar(1000);
ALTER TABLE business_bp05_snapshot ADD COLUMN demand_revision bigint;
ALTER TABLE business_bp05_snapshot ADD COLUMN snapshot_payload jsonb;
ALTER TABLE business_bp05_snapshot
    ADD CONSTRAINT business_bp05_snapshot_demand_revision_fk
    FOREIGN KEY (demand_id, demand_revision)
    REFERENCES business_demand_revision (demand_id, revision);

CREATE FUNCTION reject_append_only_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only business record cannot be changed';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER business_bid_revision_append_only
    BEFORE UPDATE OR DELETE ON business_bid_revision
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
CREATE TRIGGER business_bp05_snapshot_append_only
    BEFORE UPDATE OR DELETE ON business_bp05_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_mutation();
