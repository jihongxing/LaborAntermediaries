-- 异常解除认领时，在同一事务内为原认领人留下最小化站内通知。
CREATE TABLE platform_task_recovery_notice (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    task_id bigint NOT NULL,
    claim_id bigint NOT NULL UNIQUE,
    recipient_account_id bigint NOT NULL REFERENCES auth_account(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (task_id, tenant_id) REFERENCES platform_review_task(id, tenant_id),
    FOREIGN KEY (claim_id, task_id) REFERENCES platform_task_claim(id, task_id)
);

CREATE INDEX platform_task_recovery_notice_recipient_idx
    ON platform_task_recovery_notice (recipient_account_id, created_at DESC, id DESC);

COMMENT ON TABLE platform_task_recovery_notice IS '异常恢复后给原认领人的站内通知；仅含任务引用，不复制材料或自由文本理由';
COMMENT ON COLUMN platform_task_recovery_notice.claim_id IS '每次已恢复认领最多一条通知';
COMMENT ON COLUMN platform_task_recovery_notice.recipient_account_id IS '由服务端原认领记录确定的接收账号';

CREATE TRIGGER platform_task_recovery_notice_append_only
    BEFORE UPDATE OR DELETE ON platform_task_recovery_notice
    FOR EACH ROW EXECUTE FUNCTION platform_reject_task_history_mutation();

INSERT INTO foundation_audit_catalog (event_code, module_code, description, status) VALUES
('platform:task:recovery:notice:created', 'platform', '异常解除认领后为原认领人创建站内通知', 'CONNECTED'),
('platform:task:recovery:notice:read', 'platform', '原认领人读取本人异常恢复通知', 'CONNECTED');
