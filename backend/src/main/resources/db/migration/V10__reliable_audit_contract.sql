-- 为平台治理审计预留稳定关联字段；旧事件保持 NULL，覆盖页必须将其识别为历史缺口。
ALTER TABLE foundation_audit
    ADD COLUMN review_case_id varchar(64),
    ADD COLUMN appeal_case_id varchar(64),
    ADD COLUMN finding_id varchar(64),
    ADD COLUMN task_id varchar(64),
    ADD COLUMN resource_version bigint CHECK (resource_version IS NULL OR resource_version > 0),
    ADD COLUMN actor_role varchar(64),
    ADD COLUMN correlation_id varchar(64),
    ADD COLUMN reason_code varchar(100),
    ADD COLUMN purpose_code varchar(100);

CREATE INDEX foundation_audit_review_case_idx
    ON foundation_audit (review_case_id, created_at) WHERE review_case_id IS NOT NULL;
CREATE INDEX foundation_audit_correlation_idx
    ON foundation_audit (correlation_id) WHERE correlation_id IS NOT NULL;

-- 目录状态描述覆盖情况，不代表某个具体事件已被实际发送。
CREATE TABLE foundation_audit_catalog (
    event_code varchar(120) PRIMARY KEY,
    module_code varchar(80) NOT NULL,
    description varchar(300) NOT NULL,
    status varchar(24) NOT NULL CHECK (status IN ('NOT_IMPLEMENTED', 'PENDING', 'CONNECTED', 'DEGRADED')),
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO foundation_audit_catalog (event_code, module_code, description, status) VALUES
('foundation.request.read', 'foundation', '受保护请求读取与身份上下文建立', 'PENDING'),
('foundation.access.denied', 'foundation', '认证、权限或资源访问拒绝', 'PENDING'),
('foundation.audit.failure', 'foundation', '审计写入、读取及核对故障', 'PENDING'),
('platform.authorization.changed', 'platform', '平台岗位授权授予、变更和撤销', 'PENDING'),
('platform.organization.queue.read', 'platform', '组织审核队列读取', 'PENDING'),
('platform.organization.case.read', 'platform', '组织审核事项、版本和历史读取', 'PENDING'),
('platform.organization.review.decided', 'platform', '组织审核决定与申请方通知', 'PENDING'),
('platform.demand.queue.read', 'platform', '需求审核队列读取', 'PENDING'),
('platform.demand.case.read', 'platform', '需求审核事项、版本和历史读取', 'PENDING'),
('platform.demand.review.decided', 'platform', '需求审核决定与申请方通知', 'PENDING'),
('platform.task.claimed', 'platform', '岗位任务认领与幂等重试', 'PENDING'),
('platform.task.released', 'platform', '岗位任务主动释放或异常恢复', 'PENDING'),
('platform.task.conflict', 'platform', '认领竞争、回避或版本冲突拒绝', 'PENDING'),
('platform.appeal.created', 'platform', '审核异议创建与复核请求', 'PENDING'),
('platform.appeal.reviewed', 'platform', '独立复核结论', 'PENDING'),
('platform.evidence.accessed', 'platform', '审核材料预览、展开或访问拒绝', 'PENDING'),
('platform.audit.query', 'platform', '监察查询与事项历史读取', 'PENDING'),
('platform.audit.coverage.read', 'platform', '审计覆盖目录读取', 'PENDING'),
('platform.audit.finding.created', 'platform', '监察发现追加', 'PENDING'),
('platform.audit.finding.updated', 'platform', '监察发现回应、认领、复核或关闭追加', 'PENDING'),
('platform.audit.integrity.checked', 'platform', '审计完整性核对结果', 'PENDING'),
('identity.organization.review.decided', 'identity', '组织入驻审核决定', 'PENDING'),
('demand.review.decided', 'demand', '需求审核决定', 'PENDING'),
('supervision.action.recorded', 'supervision', '已批准治理监督操作', 'PENDING');

-- 普通应用写入身份只可追加；更正必须写入新事件。数据库高权限维护仍须受外部审批和独立记录约束。
CREATE FUNCTION foundation_reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'foundation_audit is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER foundation_audit_append_only
    BEFORE UPDATE OR DELETE ON foundation_audit
    FOR EACH ROW EXECUTE FUNCTION foundation_reject_audit_mutation();
