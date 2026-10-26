CREATE TABLE platform_authorization_role (
    role_code varchar(48) PRIMARY KEY,
    description varchar(200) NOT NULL
);

CREATE TABLE platform_authorization_role_permission (
    role_code varchar(48) NOT NULL REFERENCES platform_authorization_role(role_code),
    permission_code varchar(100) NOT NULL,
    PRIMARY KEY (role_code, permission_code)
);

INSERT INTO platform_authorization_role (role_code, description) VALUES
    ('AUTHORIZATION_ADMIN', '平台岗位授权管理员'),
    ('ORGANIZATION_REVIEWER', '组织审核员'),
    ('DEMAND_REVIEWER', '需求审核员'),
    ('INDEPENDENT_REVIEWER', '独立复核员'),
    ('TASK_RECOVERY', '例外任务恢复人员'),
    ('INDEPENDENT_AUDITOR', '平台独立监察员'),
    ('BID_SUPERVISOR', '既有应标监督能力');

INSERT INTO platform_authorization_role_permission (role_code, permission_code) VALUES
    ('AUTHORIZATION_ADMIN', 'platform:authorization:manage'),
    ('ORGANIZATION_REVIEWER', 'platform:organizations:review'),
    ('DEMAND_REVIEWER', 'platform:demands:review'),
    ('INDEPENDENT_REVIEWER', 'platform:appeals:review'),
    ('TASK_RECOVERY', 'platform:tasks:reopen'),
    ('INDEPENDENT_AUDITOR', 'platform:audit:read'),
    ('INDEPENDENT_AUDITOR', 'platform:audit:coverage:read'),
    ('INDEPENDENT_AUDITOR', 'platform:audit:findings:create'),
    ('BID_SUPERVISOR', 'platform:bids:supervise');

CREATE TABLE platform_authorization_grant (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    account_id bigint NOT NULL REFERENCES auth_account(id),
    role_code varchar(48) NOT NULL REFERENCES platform_authorization_role(role_code),
    granted_by bigint REFERENCES auth_account(id),
    granted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_authorization_expiry_check CHECK (expires_at > granted_at),
    CONSTRAINT platform_authorization_revocation_check CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
CREATE UNIQUE INDEX platform_authorization_one_active_role_per_account
    ON platform_authorization_grant(account_id) WHERE revoked_at IS NULL;
CREATE INDEX platform_authorization_active_role_idx
    ON platform_authorization_grant(role_code, expires_at) WHERE revoked_at IS NULL;

CREATE FUNCTION platform_guard_authorization_grant() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'platform authorization grants cannot be deleted';
    END IF;
    IF NEW.id <> OLD.id OR NEW.account_id <> OLD.account_id OR NEW.role_code <> OLD.role_code
        OR NEW.granted_by IS DISTINCT FROM OLD.granted_by OR NEW.granted_at <> OLD.granted_at
        OR NEW.expires_at <> OLD.expires_at OR NEW.created_at <> OLD.created_at
        OR OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL THEN
        RAISE EXCEPTION 'platform authorization grants only allow one-way revocation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_authorization_grant_immutable
    BEFORE UPDATE OR DELETE ON platform_authorization_grant
    FOR EACH ROW EXECUTE FUNCTION platform_guard_authorization_grant();

CREATE TABLE platform_authorization_change_request (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    action_code varchar(16) NOT NULL CHECK (action_code IN ('GRANT', 'RENEW', 'REVOKE', 'REPLACE')),
    target_account_id bigint NOT NULL REFERENCES auth_account(id),
    role_code varchar(48) NOT NULL REFERENCES platform_authorization_role(role_code),
    requested_by bigint NOT NULL REFERENCES auth_account(id),
    requested_expires_at timestamptz,
    reason varchar(500) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approved_by bigint REFERENCES auth_account(id),
    decision_reason varchar(500),
    requested_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at timestamptz,
    CONSTRAINT platform_authorization_request_actor_check CHECK
        (requested_by <> target_account_id AND (approved_by IS NULL OR approved_by <> target_account_id)),
    CONSTRAINT platform_authorization_request_approval_check CHECK
        ((status = 'PENDING' AND approved_by IS NULL AND decided_at IS NULL)
        OR (status <> 'PENDING' AND decided_at IS NOT NULL
            AND (approved_by IS NULL OR requested_by <> approved_by))),
    CONSTRAINT platform_authorization_request_expiry_check CHECK
        ((action_code = 'REVOKE' AND requested_expires_at IS NULL)
        OR (action_code <> 'REVOKE' AND requested_expires_at IS NOT NULL))
);
CREATE INDEX platform_authorization_request_target_idx
    ON platform_authorization_change_request(target_account_id, requested_at DESC);
CREATE INDEX platform_authorization_request_pending_idx
    ON platform_authorization_change_request(status, requested_at) WHERE status = 'PENDING';

CREATE UNIQUE INDEX platform_authorization_one_pending_request_per_target
    ON platform_authorization_change_request(target_account_id) WHERE status = 'PENDING';

CREATE FUNCTION platform_guard_authorization_request() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' OR OLD.status <> 'PENDING' OR NEW.id <> OLD.id
        OR NEW.action_code <> OLD.action_code OR NEW.target_account_id <> OLD.target_account_id
        OR NEW.role_code <> OLD.role_code OR NEW.requested_by <> OLD.requested_by
        OR NEW.requested_expires_at IS DISTINCT FROM OLD.requested_expires_at
        OR NEW.reason <> OLD.reason OR NEW.requested_at <> OLD.requested_at
        OR NEW.status = 'PENDING' THEN
        RAISE EXCEPTION 'authorization requests are immutable after their single decision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_authorization_request_immutable
    BEFORE UPDATE OR DELETE ON platform_authorization_change_request
    FOR EACH ROW EXECUTE FUNCTION platform_guard_authorization_request();

CREATE TABLE platform_authorization_event (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    request_id bigint REFERENCES platform_authorization_change_request(id),
    grant_id bigint REFERENCES platform_authorization_grant(id),
    action_code varchar(40) NOT NULL,
    target_account_id bigint NOT NULL,
    role_code varchar(48) NOT NULL REFERENCES platform_authorization_role(role_code),
    actor_account_id bigint REFERENCES auth_account(id),
    operator_id bigint,
    approver_account_id bigint REFERENCES auth_account(id),
    outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCESS', 'PENDING', 'DENIED', 'FAILED')),
    reason varchar(500) NOT NULL,
    expires_at timestamptz,
    occurred_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT platform_authorization_event_actor_check CHECK
        ((actor_account_id IS NOT NULL AND operator_id IS NULL)
        OR (actor_account_id IS NULL AND operator_id IS NOT NULL)),
    CONSTRAINT platform_authorization_event_approver_check CHECK
        (approver_account_id IS NULL OR actor_account_id IS NULL OR approver_account_id <> actor_account_id)
);
CREATE INDEX platform_authorization_event_target_idx
    ON platform_authorization_event(target_account_id, occurred_at);

CREATE FUNCTION platform_reject_authorization_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform authorization events are append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_authorization_event_append_only
    BEFORE UPDATE OR DELETE ON platform_authorization_event
    FOR EACH ROW EXECUTE FUNCTION platform_reject_authorization_event_mutation();

CREATE TABLE platform_authorization_bootstrap (
    singleton_id smallint PRIMARY KEY CHECK (singleton_id = 1),
    grant_id bigint NOT NULL UNIQUE REFERENCES platform_authorization_grant(id),
    target_account_id bigint NOT NULL REFERENCES auth_account(id),
    operator_id bigint NOT NULL CHECK (operator_id > 0),
    reason varchar(500) NOT NULL,
    expires_at timestamptz NOT NULL,
    initialized_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER platform_authorization_bootstrap_immutable
    BEFORE UPDATE OR DELETE ON platform_authorization_bootstrap
    FOR EACH ROW EXECUTE FUNCTION platform_reject_authorization_event_mutation();
