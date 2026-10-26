ALTER TABLE foundation_audit
    ADD COLUMN previous_hash varchar(64),
    ADD COLUMN event_hash varchar(64),
    ADD CONSTRAINT foundation_audit_hash_pair
        CHECK ((previous_hash IS NULL) = (event_hash IS NULL)),
    ADD CONSTRAINT foundation_audit_hash_format
        CHECK (event_hash IS NULL OR event_hash ~ '^[0-9a-f]{64}$');

CREATE UNIQUE INDEX foundation_audit_event_hash_unique
    ON foundation_audit (event_hash) WHERE event_hash IS NOT NULL;

-- Existing rows remain an explicit unhashed history gap; new events start after this watermark.
CREATE TABLE foundation_audit_chain (
    chain_id smallint PRIMARY KEY CHECK (chain_id = 1),
    legacy_through_event_id bigint NOT NULL,
    legacy_event_count bigint NOT NULL CHECK (legacy_event_count >= 0),
    last_event_id bigint,
    last_hash varchar(64),
    CHECK ((last_event_id IS NULL) = (last_hash IS NULL)),
    CHECK (last_hash IS NULL OR last_hash ~ '^[0-9a-f]{64}$')
);

INSERT INTO foundation_audit_chain (chain_id, legacy_through_event_id, legacy_event_count)
SELECT 1, COALESCE(MAX(id), 0), COUNT(*) FROM foundation_audit;

CREATE FUNCTION foundation_guard_audit_chain_update() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'foundation_audit_chain is append-only';
    END IF;

    IF NEW.chain_id <> OLD.chain_id
        OR NEW.legacy_through_event_id <> OLD.legacy_through_event_id
        OR NEW.legacy_event_count <> OLD.legacy_event_count THEN
        RAISE EXCEPTION 'foundation_audit_chain baseline is immutable';
    END IF;

    IF NEW.last_event_id IS NULL OR NEW.last_hash IS NULL
        OR NEW.last_event_id <= COALESCE(OLD.last_event_id, OLD.legacy_through_event_id)
        OR NOT EXISTS (
            SELECT 1 FROM foundation_audit
            WHERE id = NEW.last_event_id
                AND event_hash = NEW.last_hash
                AND previous_hash IS NOT DISTINCT FROM OLD.last_hash
        ) THEN
        RAISE EXCEPTION 'foundation_audit_chain head must reference the next appended event';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER foundation_audit_chain_immutable
    BEFORE UPDATE OR DELETE ON foundation_audit_chain
    FOR EACH ROW EXECUTE FUNCTION foundation_guard_audit_chain_update();

CREATE FUNCTION foundation_check_audit_append() RETURNS trigger AS $$
DECLARE
    current_event_id bigint;
    current_hash varchar(64);
    history_watermark bigint;
BEGIN
    SELECT last_event_id, last_hash, legacy_through_event_id
    INTO current_event_id, current_hash, history_watermark
    FROM foundation_audit_chain WHERE chain_id = 1 FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'foundation_audit_chain state is missing';
    END IF;

    IF NEW.id <= COALESCE(current_event_id, history_watermark)
        OR NEW.previous_hash IS DISTINCT FROM current_hash
        OR NEW.event_hash IS NULL THEN
        RAISE EXCEPTION 'foundation_audit event is not the next sealed append';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER foundation_audit_validate_append
    BEFORE INSERT ON foundation_audit
    FOR EACH ROW EXECUTE FUNCTION foundation_check_audit_append();

CREATE FUNCTION foundation_advance_audit_chain() RETURNS trigger AS $$
BEGIN
    UPDATE foundation_audit_chain
    SET last_event_id = NEW.id, last_hash = NEW.event_hash
    WHERE chain_id = 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'foundation_audit_chain state is missing';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER foundation_audit_advance_chain
    AFTER INSERT ON foundation_audit
    FOR EACH ROW EXECUTE FUNCTION foundation_advance_audit_chain();
