-- 为首次提交的组织申请和需求建立稳定事项编号；旧审计事件不按资源编号推测回填。
CREATE TABLE platform_review_case (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL REFERENCES business_organization(id),
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('ORGANIZATION', 'DEMAND')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    initial_version bigint CHECK (initial_version IS NULL OR initial_version > 0),
    opened_by bigint REFERENCES auth_account(id),
    opened_at timestamptz,
    history_coverage varchar(24) NOT NULL DEFAULT 'COMPLETE'
        CHECK (history_coverage IN ('COMPLETE', 'LEGACY_GAP')),
    UNIQUE (tenant_id, resource_type, resource_id),
    UNIQUE (id, tenant_id),
    CHECK ((history_coverage = 'COMPLETE' AND initial_version IS NOT NULL
            AND opened_by IS NOT NULL AND opened_at IS NOT NULL)
        OR (history_coverage = 'LEGACY_GAP' AND initial_version IS NULL AND opened_at IS NULL))
);

CREATE INDEX platform_review_case_tenant_idx
    ON platform_review_case (tenant_id, opened_at DESC, id DESC);

COMMENT ON TABLE platform_review_case IS '组织申请或需求首次提交时创建的稳定审核事项映射，不替代业务状态或版本真源';
COMMENT ON COLUMN platform_review_case.id IS '稳定审核事项编号；API 以十进制字符串返回';
COMMENT ON COLUMN platform_review_case.tenant_id IS '资源归属组织，用于审核事项的组织隔离';
COMMENT ON COLUMN platform_review_case.resource_type IS '事项资源类型：组织申请或需求审核';
COMMENT ON COLUMN platform_review_case.resource_id IS '组织或需求业务资源编号';
COMMENT ON COLUMN platform_review_case.initial_version IS '事项首次提交的业务版本；迁移前事项未知时为空';
COMMENT ON COLUMN platform_review_case.opened_by IS '首次提交该事项的账号；迁移前事项未知时为空';
COMMENT ON COLUMN platform_review_case.opened_at IS '事项创建时间；迁移前事项无法可靠确定时为空';
COMMENT ON COLUMN platform_review_case.history_coverage IS '完整或存在迁移前审计关联缺口；旧审计关联不推测回填';

-- 只从明确的非草稿业务状态建立旧事项映射；不将旧审计事件按资源号猜测关联。
INSERT INTO platform_review_case (tenant_id, resource_type, resource_id, history_coverage)
SELECT id, 'ORGANIZATION', id, 'LEGACY_GAP'
FROM business_organization
WHERE status <> 'DRAFT'
ON CONFLICT (tenant_id, resource_type, resource_id) DO NOTHING;

INSERT INTO platform_review_case (tenant_id, resource_type, resource_id, history_coverage)
SELECT tenant_id, 'DEMAND', id, 'LEGACY_GAP'
FROM business_demand
WHERE status <> 'DRAFT'
ON CONFLICT (tenant_id, resource_type, resource_id) DO NOTHING;

CREATE FUNCTION platform_guard_review_case_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform review case identity is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_review_case_immutable
    BEFORE UPDATE OR DELETE ON platform_review_case
    FOR EACH ROW EXECUTE FUNCTION platform_guard_review_case_immutable();

INSERT INTO foundation_audit_catalog (event_code, module_code, description, status) VALUES
('platform:review_case:history:read', 'platform', '申请方读取本人组织或需求审核事项历史', 'CONNECTED'),
('platform:review_case:history:denied', 'platform', '审核事项历史越权读取拒绝', 'CONNECTED');
