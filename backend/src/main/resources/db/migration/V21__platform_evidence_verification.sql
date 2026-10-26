CREATE TABLE platform_review_evidence_verification (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL,
    review_case_id bigint NOT NULL REFERENCES platform_review_case(id),
    task_id bigint NOT NULL REFERENCES platform_review_task(id),
    evidence_id bigint NOT NULL REFERENCES platform_review_evidence(id),
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('ORGANIZATION', 'DEMAND')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    dimension varchar(24) NOT NULL CHECK (dimension IN ('COMPLETENESS', 'AUTHENTICITY', 'VALIDITY')),
    result varchar(24) NOT NULL,
    source_label varchar(200) NOT NULL CHECK (length(btrim(source_label)) > 0),
    source_reference varchar(200),
    basis varchar(2000) NOT NULL CHECK (length(btrim(basis)) > 0),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT platform_review_evidence_verification_result_check CHECK (
        (dimension = 'COMPLETENESS' AND result IN ('COMPLETE', 'MISSING')) OR
        (dimension = 'AUTHENTICITY' AND result IN ('VERIFIED', 'NOT_VERIFIED', 'CONFLICT')) OR
        (dimension = 'VALIDITY' AND result IN ('VALID', 'EXPIRED', 'NOT_APPLICABLE', 'NOT_VERIFIED'))
    )
);

CREATE INDEX platform_review_evidence_verification_task_idx
    ON platform_review_evidence_verification(task_id, evidence_id, created_at, id);

COMMENT ON TABLE platform_review_evidence_verification IS
    '只追加的审核材料逐项核验记录；材料扫描、安全、真实性和有效性结论分别保存';

CREATE FUNCTION platform_reject_review_evidence_verification_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform review evidence verification is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_review_evidence_verification_append_only
    BEFORE UPDATE OR DELETE ON platform_review_evidence_verification
    FOR EACH ROW EXECUTE FUNCTION platform_reject_review_evidence_verification_mutation();

INSERT INTO foundation_audit_catalog(event_code, module_code, description, status)
VALUES ('platform:evidence:verification:read', 'platform', '审核员读取材料逐项核验记录', 'CONNECTED'),
       ('platform:evidence:verification:recorded', 'platform', '审核员追加材料逐项核验记录', 'CONNECTED')
ON CONFLICT (event_code) DO UPDATE SET
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    status = EXCLUDED.status;
