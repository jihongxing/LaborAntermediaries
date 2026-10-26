CREATE TABLE platform_review_correction_item (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL,
    review_case_id bigint NOT NULL REFERENCES platform_review_case(id),
    task_id bigint NOT NULL REFERENCES platform_review_task(id),
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('ORGANIZATION', 'DEMAND')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    item_code varchar(64) NOT NULL CHECK (length(btrim(item_code)) > 0),
    title varchar(120) NOT NULL CHECK (length(btrim(title)) > 0),
    description varchar(1000) NOT NULL CHECK (length(btrim(description)) > 0),
    applicant_action varchar(1000) NOT NULL CHECK (length(btrim(applicant_action)) > 0),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT platform_review_correction_item_version_code_uk
        UNIQUE (review_case_id, resource_version, item_code)
);

CREATE INDEX platform_review_correction_item_case_version_idx
    ON platform_review_correction_item(review_case_id, resource_version, id);

COMMENT ON TABLE platform_review_correction_item IS
    '绑定组织/需求审核事项版本的只追加逐项补正清单';
COMMENT ON COLUMN platform_review_correction_item.applicant_action IS
    '申请方可理解且可执行的补正动作，不包含内部监察注记';

CREATE FUNCTION platform_reject_review_correction_item_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform review correction items are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_review_correction_item_append_only
    BEFORE UPDATE OR DELETE ON platform_review_correction_item
    FOR EACH ROW EXECUTE FUNCTION platform_reject_review_correction_item_mutation();

INSERT INTO foundation_audit_catalog(event_code, module_code, description, status)
VALUES ('platform:review:correction-items:recorded', 'platform', '审核结论逐项补正清单已追加', 'CONNECTED')
ON CONFLICT (event_code) DO UPDATE SET
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    status = EXCLUDED.status;
