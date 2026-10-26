-- 为审核事项建立版本绑定的岗位任务、独立复核事项及不可覆盖的任务处理记录。
CREATE TABLE platform_appeal_case (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    review_case_id bigint NOT NULL,
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    original_decision_id bigint NOT NULL REFERENCES business_review_decision(id),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    requested_by bigint NOT NULL REFERENCES auth_account(id),
    request_reason varchar(2000) NOT NULL,
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_REVIEW', 'REVIEWED', 'SUPERSEDED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (review_case_id, tenant_id) REFERENCES platform_review_case(id, tenant_id)
);

CREATE UNIQUE INDEX platform_appeal_case_one_open_per_decision_idx
    ON platform_appeal_case (original_decision_id)
    WHERE status IN ('PENDING', 'IN_REVIEW');
CREATE INDEX platform_appeal_case_requester_idx
    ON platform_appeal_case (requested_by, created_at DESC, id DESC);

COMMENT ON TABLE platform_appeal_case IS '关联原审核事项、决定及版本的独立复核申请；不覆盖原业务决定';
COMMENT ON COLUMN platform_appeal_case.review_case_id IS '原组织或需求审核事项编号';
COMMENT ON COLUMN platform_appeal_case.original_decision_id IS '被申请复核的原审核决定';
COMMENT ON COLUMN platform_appeal_case.resource_version IS '原决定所绑定的申请版本';
COMMENT ON COLUMN platform_appeal_case.request_reason IS '申请方提交的复核理由，仅在已认领的复核任务范围内可见';

CREATE TABLE platform_review_task (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    review_case_id bigint NOT NULL,
    appeal_case_id bigint,
    task_type varchar(32) NOT NULL CHECK (task_type IN ('ORGANIZATION_REVIEW', 'DEMAND_REVIEW', 'APPEAL_REVIEW')),
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('ORGANIZATION', 'DEMAND')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    status varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETED', 'SUPERSEDED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, tenant_id),
    FOREIGN KEY (review_case_id, tenant_id) REFERENCES platform_review_case(id, tenant_id),
    FOREIGN KEY (appeal_case_id, tenant_id) REFERENCES platform_appeal_case(id, tenant_id),
    CHECK ((task_type = 'APPEAL_REVIEW' AND appeal_case_id IS NOT NULL)
        OR (task_type <> 'APPEAL_REVIEW' AND appeal_case_id IS NULL))
);

CREATE UNIQUE INDEX platform_review_task_version_idx
    ON platform_review_task (review_case_id, task_type, resource_version)
    WHERE task_type <> 'APPEAL_REVIEW';
CREATE UNIQUE INDEX platform_review_task_appeal_idx
    ON platform_review_task (appeal_case_id) WHERE appeal_case_id IS NOT NULL;
CREATE INDEX platform_review_task_queue_idx
    ON platform_review_task (task_type, status, created_at, id);

COMMENT ON TABLE platform_review_task IS '岗位对指定审核事项版本的自主认领任务，不表示平台人员分派';
COMMENT ON COLUMN platform_review_task.review_case_id IS '稳定审核事项编号';
COMMENT ON COLUMN platform_review_task.appeal_case_id IS '独立复核事项编号；普通审核任务为空';
COMMENT ON COLUMN platform_review_task.resource_version IS '任务绑定的唯一业务版本；不可随资源当前版本变更';

CREATE TABLE platform_task_claim (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    task_id bigint NOT NULL REFERENCES platform_review_task(id),
    actor_id bigint NOT NULL REFERENCES auth_account(id),
    status varchar(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'RELEASED', 'RECOVERED', 'RECUSED', 'COMPLETED', 'SUPERSEDED')),
    claimed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    closed_at timestamptz,
    close_reason varchar(2000),
    UNIQUE (id, task_id),
    CHECK ((status = 'ACTIVE' AND closed_at IS NULL AND close_reason IS NULL)
        OR (status <> 'ACTIVE' AND closed_at IS NOT NULL AND close_reason IS NOT NULL))
);

CREATE UNIQUE INDEX platform_task_claim_one_active_idx
    ON platform_task_claim (task_id) WHERE status = 'ACTIVE';
CREATE INDEX platform_task_claim_actor_idx ON platform_task_claim (actor_id, claimed_at DESC, id DESC);

COMMENT ON TABLE platform_task_claim IS '任务每次认领的不可删除历史；关闭原因保留于本行';

CREATE TABLE platform_task_claim_request (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    task_id bigint NOT NULL,
    actor_id bigint NOT NULL REFERENCES auth_account(id),
    idempotency_key varchar(128) NOT NULL
        CHECK (idempotency_key ~ '^[a-zA-Z0-9:._-]{1,128}$'),
    claim_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, actor_id, idempotency_key),
    FOREIGN KEY (claim_id, task_id) REFERENCES platform_task_claim(id, task_id)
);

COMMENT ON TABLE platform_task_claim_request IS '认领请求幂等键到账户、任务和原认领结果的稳定映射';

CREATE TABLE platform_task_conflict (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    task_id bigint NOT NULL REFERENCES platform_review_task(id),
    subject_account_id bigint NOT NULL REFERENCES auth_account(id),
    reported_by bigint NOT NULL REFERENCES auth_account(id),
    conflict_type varchar(32) NOT NULL CHECK (conflict_type IN ('SELF_DECLARED', 'PARTY_REQUESTED')),
    reason varchar(2000) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (task_id, subject_account_id, conflict_type)
);

CREATE INDEX platform_task_conflict_subject_idx
    ON platform_task_conflict (subject_account_id, task_id);
COMMENT ON TABLE platform_task_conflict IS '针对具体任务追加的自我申报或当事方回避请求；系统关系匹配不代表发现全部冲突';

CREATE TABLE platform_appeal_review_result (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    appeal_case_id bigint NOT NULL UNIQUE REFERENCES platform_appeal_case(id),
    reviewer_id bigint NOT NULL REFERENCES auth_account(id),
    outcome varchar(40) NOT NULL CHECK (outcome IN ('UPHOLD_ORIGINAL', 'REQUEST_RECONSIDERATION')),
    reason varchar(2000) NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE platform_appeal_review_result IS '独立复核意见；记录意见但不直接改变原审核决定或业务状态';

CREATE FUNCTION platform_guard_review_task_transition() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id
        OR NEW.review_case_id <> OLD.review_case_id OR NEW.appeal_case_id IS DISTINCT FROM OLD.appeal_case_id
        OR NEW.task_type <> OLD.task_type OR NEW.resource_type <> OLD.resource_type
        OR NEW.resource_id <> OLD.resource_id OR NEW.resource_version <> OLD.resource_version
        OR NEW.created_at <> OLD.created_at
        OR NOT ((OLD.status = 'PENDING' AND NEW.status IN ('CLAIMED', 'SUPERSEDED'))
            OR (OLD.status = 'CLAIMED' AND NEW.status IN ('PENDING', 'COMPLETED', 'SUPERSEDED'))) THEN
        RAISE EXCEPTION 'platform review task identity and terminal state are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_review_task_transition
    BEFORE UPDATE OR DELETE ON platform_review_task
    FOR EACH ROW EXECUTE FUNCTION platform_guard_review_task_transition();

CREATE FUNCTION platform_guard_task_claim_transition() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.id <> OLD.id OR NEW.task_id <> OLD.task_id
        OR NEW.actor_id <> OLD.actor_id OR NEW.claimed_at <> OLD.claimed_at
        OR OLD.status <> 'ACTIVE' OR NEW.status NOT IN ('RELEASED', 'RECOVERED', 'RECUSED', 'COMPLETED', 'SUPERSEDED')
        OR NEW.closed_at IS NULL OR NEW.close_reason IS NULL THEN
        RAISE EXCEPTION 'platform task claims are append-only with one-way closure';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_task_claim_transition
    BEFORE UPDATE OR DELETE ON platform_task_claim
    FOR EACH ROW EXECUTE FUNCTION platform_guard_task_claim_transition();

CREATE FUNCTION platform_reject_task_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform task history is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_task_claim_request_append_only
    BEFORE UPDATE OR DELETE ON platform_task_claim_request
    FOR EACH ROW EXECUTE FUNCTION platform_reject_task_history_mutation();
CREATE TRIGGER platform_task_conflict_append_only
    BEFORE UPDATE OR DELETE ON platform_task_conflict
    FOR EACH ROW EXECUTE FUNCTION platform_reject_task_history_mutation();
CREATE TRIGGER platform_appeal_review_result_append_only
    BEFORE UPDATE OR DELETE ON platform_appeal_review_result
    FOR EACH ROW EXECUTE FUNCTION platform_reject_task_history_mutation();

CREATE FUNCTION platform_guard_appeal_case_transition() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.id <> OLD.id OR NEW.review_case_id <> OLD.review_case_id
        OR NEW.tenant_id <> OLD.tenant_id OR NEW.original_decision_id <> OLD.original_decision_id
        OR NEW.resource_version <> OLD.resource_version OR NEW.requested_by <> OLD.requested_by
        OR NEW.request_reason <> OLD.request_reason OR NEW.created_at <> OLD.created_at
        OR NOT ((OLD.status = 'PENDING' AND NEW.status IN ('IN_REVIEW', 'SUPERSEDED'))
            OR (OLD.status = 'IN_REVIEW' AND NEW.status IN ('PENDING', 'REVIEWED', 'SUPERSEDED'))) THEN
        RAISE EXCEPTION 'platform appeal identity and terminal state are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_appeal_case_transition
    BEFORE UPDATE OR DELETE ON platform_appeal_case
    FOR EACH ROW EXECUTE FUNCTION platform_guard_appeal_case_transition();

-- 为部署升级时仍处于待审核状态的事项建立当前版本任务；历史关联缺口仍由 V18 明确保留。
INSERT INTO platform_review_task
    (tenant_id, review_case_id, task_type, resource_type, resource_id, resource_version, status)
SELECT o.tenant_id, c.id, 'ORGANIZATION_REVIEW', 'ORGANIZATION', o.id, o.application_version, 'PENDING'
FROM business_organization o
JOIN platform_review_case c ON c.tenant_id = o.tenant_id
    AND c.resource_type = 'ORGANIZATION' AND c.resource_id = o.id
WHERE o.status = 'PENDING_REVIEW'
ON CONFLICT DO NOTHING;

INSERT INTO platform_review_task
    (tenant_id, review_case_id, task_type, resource_type, resource_id, resource_version, status)
SELECT d.tenant_id, c.id, 'DEMAND_REVIEW', 'DEMAND', d.id, d.current_revision, 'PENDING'
FROM business_demand d
JOIN platform_review_case c ON c.tenant_id = d.tenant_id
    AND c.resource_type = 'DEMAND' AND c.resource_id = d.id
WHERE d.status = 'PENDING_REVIEW'
ON CONFLICT DO NOTHING;

INSERT INTO foundation_audit_catalog (event_code, module_code, description, status) VALUES
('platform:task:created', 'platform', '提交组织或需求版本时建立岗位任务', 'CONNECTED'),
('platform:task:queue:read', 'platform', '平台岗位读取最小任务队列', 'CONNECTED'),
('platform:task:detail:read', 'platform', '平台岗位读取已认领任务及事项历史', 'CONNECTED'),
('platform:task:access:denied', 'platform', '平台任务访问拒绝或目标不存在', 'CONNECTED'),
('platform:task:claimed', 'platform', '平台岗位成功原子认领任务', 'CONNECTED'),
('platform:task:claim:retry', 'platform', '平台任务认领幂等重试', 'CONNECTED'),
('platform:task:claim:conflict', 'platform', '平台任务认领冲突或回避拒绝', 'CONNECTED'),
('platform:task:released', 'platform', '平台审核员主动释放任务', 'CONNECTED'),
('platform:task:recovered', 'platform', '独立恢复岗位解除占用并退回队列', 'CONNECTED'),
('platform:task:recused', 'platform', '平台审核员自我申报回避', 'CONNECTED'),
('platform:task:recusal:requested', 'platform', '事项当事方请求审核员回避', 'CONNECTED'),
('platform:task:review:denied', 'platform', '无有效认领或审核资格拒绝提交决定', 'CONNECTED'),
('platform:task:completed', 'platform', '与业务审核决定共同完成岗位任务', 'CONNECTED'),
('platform:task:superseded', 'platform', '新版本提交后关闭旧版本任务', 'CONNECTED'),
('platform:appeal:created', 'platform', '申请方创建关联原决定的独立复核事项', 'CONNECTED'),
('platform:appeal:create:denied', 'platform', '独立复核申请越权、过期或冲突拒绝', 'CONNECTED'),
('platform:appeal:create:conflict', 'platform', '同一决定已有未结独立复核申请', 'CONNECTED'),
('platform:appeal:review:denied', 'platform', '独立复核任务类型或状态拒绝', 'CONNECTED'),
('platform:appeal:reviewed', 'platform', '独立复核员记录复核意见', 'CONNECTED')
ON CONFLICT (event_code) DO UPDATE
SET module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    status = EXCLUDED.status;
