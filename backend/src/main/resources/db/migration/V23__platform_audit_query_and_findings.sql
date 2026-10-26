-- G9：为跨组织监察查询保存结构化筛选，为监察发现保存追加式工作流。
CREATE TABLE platform_audit_query (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    actor_id bigint NOT NULL REFERENCES auth_account(id),
    organization_id bigint,
    review_case_id bigint,
    resource_id bigint,
    resource_version bigint,
    event_actor_id bigint,
    actor_role varchar(48),
    action varchar(100),
    outcome varchar(16),
    from_at timestamptz,
    to_at timestamptz,
    purpose_code varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (organization_id IS NULL OR organization_id > 0),
    CHECK (review_case_id IS NULL OR review_case_id > 0),
    CHECK (resource_id IS NULL OR resource_id > 0),
    CHECK (resource_version IS NULL OR resource_version > 0),
    CHECK (event_actor_id IS NULL OR event_actor_id > 0),
    CHECK (from_at IS NULL OR to_at IS NULL OR from_at <= to_at)
);

CREATE INDEX platform_audit_query_actor_idx ON platform_audit_query(actor_id, created_at DESC, id DESC);

COMMENT ON TABLE platform_audit_query IS '监察查询的结构化范围和用途记录；不保存查询结果或敏感正文';

CREATE TABLE platform_audit_finding (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint,
    review_case_id bigint,
    resource_type varchar(32),
    resource_id bigint,
    resource_version bigint,
    event_id bigint REFERENCES foundation_audit(id),
    evidence_id bigint,
    title varchar(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description varchar(4000) NOT NULL CHECK (length(btrim(description)) > 0),
    fact_status varchar(24) NOT NULL DEFAULT 'UNCONFIRMED'
        CHECK (fact_status IN ('UNCONFIRMED', 'SUPPORTED', 'NOT_SUPPORTED')),
    workflow_status varchar(32) NOT NULL DEFAULT 'WAITING_RESPONSE'
        CHECK (workflow_status IN ('WAITING_RESPONSE', 'RESPONSE_CLAIMED', 'WAITING_REVIEW',
                                   'REVIEW_CLAIMED', 'CLOSED')),
    created_by bigint NOT NULL REFERENCES auth_account(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (tenant_id IS NULL OR tenant_id > 0),
    CHECK (review_case_id IS NULL OR review_case_id > 0),
    CHECK (resource_id IS NULL OR resource_id > 0),
    CHECK (resource_version IS NULL OR resource_version > 0),
    CHECK (evidence_id IS NULL OR evidence_id > 0),
    CHECK ((resource_type IS NULL AND resource_id IS NULL) OR
           (resource_type IN ('ORGANIZATION', 'DEMAND') AND resource_id IS NOT NULL)),
    CHECK ((workflow_status = 'WAITING_RESPONSE' AND fact_status = 'UNCONFIRMED')
        OR workflow_status <> 'WAITING_RESPONSE')
);

CREATE INDEX platform_audit_finding_queue_idx
    ON platform_audit_finding(workflow_status, created_at, id);
CREATE INDEX platform_audit_finding_case_idx
    ON platform_audit_finding(review_case_id, resource_version, id);

COMMENT ON TABLE platform_audit_finding IS '监察发现及其独立于业务状态的事实状态；发现本身不构成违规或业务决定';

CREATE TABLE platform_audit_finding_entry (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    finding_id bigint NOT NULL REFERENCES platform_audit_finding(id),
    entry_type varchar(24) NOT NULL CHECK (entry_type IN ('CREATED', 'RESPONSE', 'REVIEW', 'CLOSED')),
    actor_id bigint NOT NULL REFERENCES auth_account(id),
    content varchar(4000) NOT NULL CHECK (length(btrim(content)) > 0),
    fact_status varchar(24) CHECK (fact_status IS NULL OR fact_status IN ('UNCONFIRMED', 'SUPPORTED', 'NOT_SUPPORTED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX platform_audit_finding_entry_idx
    ON platform_audit_finding_entry(finding_id, created_at, id);

CREATE FUNCTION platform_reject_audit_finding_entry_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform audit finding entries are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_audit_finding_entry_append_only
    BEFORE UPDATE OR DELETE ON platform_audit_finding_entry
    FOR EACH ROW EXECUTE FUNCTION platform_reject_audit_finding_entry_mutation();

CREATE TABLE platform_audit_finding_claim (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    finding_id bigint NOT NULL REFERENCES platform_audit_finding(id),
    stage varchar(16) NOT NULL CHECK (stage IN ('RESPONSE', 'REVIEW')),
    actor_id bigint NOT NULL REFERENCES auth_account(id),
    status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'RELEASED', 'RECUSED')),
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[a-zA-Z0-9:._-]{1,128}$'),
    claimed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    closed_at timestamptz,
    close_reason varchar(500),
    CHECK ((status = 'ACTIVE' AND closed_at IS NULL AND close_reason IS NULL)
        OR (status <> 'ACTIVE' AND closed_at IS NOT NULL AND close_reason IS NOT NULL))
);

CREATE UNIQUE INDEX platform_audit_finding_active_claim_idx
    ON platform_audit_finding_claim(finding_id, stage) WHERE status = 'ACTIVE';
CREATE UNIQUE INDEX platform_audit_finding_claim_idempotency_idx
    ON platform_audit_finding_claim(finding_id, stage, actor_id, idempotency_key);

CREATE FUNCTION platform_guard_audit_finding_claim() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.id <> OLD.id OR NEW.finding_id <> OLD.finding_id
        OR NEW.stage <> OLD.stage OR NEW.actor_id <> OLD.actor_id
        OR NEW.idempotency_key <> OLD.idempotency_key OR NEW.claimed_at <> OLD.claimed_at
        OR OLD.status <> 'ACTIVE' OR NEW.status = 'ACTIVE'
        OR NEW.closed_at IS NULL OR NEW.close_reason IS NULL THEN
        RAISE EXCEPTION 'platform audit finding claims are append-only with one-way closure';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_audit_finding_claim_transition
    BEFORE UPDATE OR DELETE ON platform_audit_finding_claim
    FOR EACH ROW EXECUTE FUNCTION platform_guard_audit_finding_claim();

CREATE TABLE platform_audit_finding_claim_request (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    finding_id bigint NOT NULL REFERENCES platform_audit_finding(id),
    stage varchar(16) NOT NULL CHECK (stage IN ('RESPONSE', 'REVIEW')),
    actor_id bigint NOT NULL REFERENCES auth_account(id),
    idempotency_key varchar(128) NOT NULL CHECK (idempotency_key ~ '^[a-zA-Z0-9:._-]{1,128}$'),
    claim_id bigint NOT NULL REFERENCES platform_audit_finding_claim(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (finding_id, stage, actor_id, idempotency_key)
);

CREATE FUNCTION platform_reject_audit_finding_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform audit finding history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_audit_finding_claim_request_append_only
    BEFORE UPDATE OR DELETE ON platform_audit_finding_claim_request
    FOR EACH ROW EXECUTE FUNCTION platform_reject_audit_finding_history_mutation();

INSERT INTO platform_authorization_role_permission(role_code, permission_code)
VALUES ('INDEPENDENT_AUDITOR', 'platform:audit:findings:respond'),
       ('INDEPENDENT_AUDITOR', 'platform:audit:findings:review')
ON CONFLICT DO NOTHING;

INSERT INTO foundation_audit_catalog(event_code, module_code, description, status)
VALUES ('platform:audit:query', 'platform', '跨组织监察查询及事项历史读取', 'CONNECTED'),
       ('platform:audit:coverage:read', 'platform', '全模块审计覆盖目录读取', 'CONNECTED'),
       ('platform:audit:finding:created', 'platform', '监察发现追加', 'CONNECTED'),
       ('platform:audit:finding:updated', 'platform', '监察发现回应、认领、复核或关闭追加', 'CONNECTED'),
       ('platform:audit:integrity:checked', 'platform', '审计完整性核对结果', 'PENDING')
ON CONFLICT (event_code) DO UPDATE SET
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    status = CASE WHEN foundation_audit_catalog.status = 'DEGRADED'
                  THEN foundation_audit_catalog.status ELSE EXCLUDED.status END;
