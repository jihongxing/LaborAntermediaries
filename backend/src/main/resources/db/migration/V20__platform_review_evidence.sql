ALTER TABLE foundation_file
    ADD CONSTRAINT foundation_file_tenant_id_pair_unique UNIQUE (tenant_id, id);

CREATE TABLE platform_review_evidence (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL,
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('ORGANIZATION', 'DEMAND')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    file_id varchar(64) NOT NULL,
    category varchar(32) NOT NULL CHECK (category IN ('AUTHORIZATION', 'QUALIFICATION', 'SUPPORTING_DOCUMENT', 'OTHER')),
    media_type varchar(64) NOT NULL CHECK (media_type IN ('application/pdf', 'image/jpeg', 'image/png')),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    scan_status varchar(16) NOT NULL DEFAULT 'CLEAN' CHECK (scan_status = 'CLEAN'),
    scanner_code varchar(32) NOT NULL CHECK (scanner_code = 'CLAMAV'),
    scanned_at timestamptz NOT NULL,
    authenticity_status varchar(32) NOT NULL DEFAULT 'NOT_VERIFIED'
        CHECK (authenticity_status = 'NOT_VERIFIED'),
    source_code varchar(32) NOT NULL DEFAULT 'APPLICANT_UPLOAD' CHECK (source_code = 'APPLICANT_UPLOAD'),
    uploaded_by bigint NOT NULL CHECK (uploaded_by > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_review_evidence_file_same_tenant_fk
        FOREIGN KEY (tenant_id, file_id) REFERENCES foundation_file(tenant_id, id),
    CONSTRAINT platform_review_evidence_resource_file_unique
        UNIQUE (tenant_id, resource_type, resource_id, resource_version, file_id)
);

CREATE INDEX platform_review_evidence_resource_idx
    ON platform_review_evidence(tenant_id, resource_type, resource_id, resource_version, id);

COMMENT ON TABLE platform_review_evidence IS '经内容类型约束和恶意文件扫描通过后追加绑定到单一组织/需求版本的私有审核材料';
COMMENT ON COLUMN platform_review_evidence.authenticity_status IS '真实性核验独立于文件安全扫描，上传后固定为未核实，不能因扫描通过而标记真实';
COMMENT ON COLUMN platform_review_evidence.source_code IS '材料提交来源；平台系统只记录申请方上传，不推断外部来源真实性';

CREATE FUNCTION platform_reject_review_evidence_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform review evidence is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_review_evidence_append_only
    BEFORE UPDATE OR DELETE ON platform_review_evidence
    FOR EACH ROW EXECUTE FUNCTION platform_reject_review_evidence_mutation();

INSERT INTO foundation_audit_catalog(event_code, module_code, description, status)
VALUES ('platform:evidence:attached', 'platform', '审核材料绑定与扫描结果', 'CONNECTED'),
       ('file.evidence.scan', 'foundation', '申请方本人私有文件证据扫描读取', 'CONNECTED')
ON CONFLICT (event_code) DO UPDATE SET
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    status = EXCLUDED.status;

UPDATE foundation_audit_catalog
SET status = 'CONNECTED'
WHERE event_code = 'platform:evidence:accessed';
