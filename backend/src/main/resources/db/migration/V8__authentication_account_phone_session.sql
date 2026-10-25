-- 认证账号是跨组织共享主体；组织成员和业务关系仍由各自业务表授权。
CREATE TABLE auth_account (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    status varchar(40) NOT NULL,
    display_name varchar(100),
    phone_verified_at timestamptz,
    failed_login_attempts integer NOT NULL DEFAULT 0 CHECK (failed_login_attempts >= 0),
    locked_until timestamptz,
    credential_version integer NOT NULL DEFAULT 1 CHECK (credential_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_account_status_check CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED'))
);

CREATE TABLE auth_login_identity (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    account_id bigint NOT NULL REFERENCES auth_account(id),
    provider varchar(40) NOT NULL,
    subject_hash varchar(128) NOT NULL,
    subject_ciphertext text,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE',
    verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_login_identity_status_check CHECK (status IN ('ACTIVE', 'REVOKED')),
    UNIQUE (provider, subject_hash)
);
CREATE INDEX auth_login_identity_account_idx ON auth_login_identity(account_id, status);

CREATE TABLE auth_credential (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    account_id bigint NOT NULL REFERENCES auth_account(id),
    credential_type varchar(30) NOT NULL,
    password_hash varchar(255) NOT NULL,
    password_updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (account_id, credential_type),
    CONSTRAINT auth_credential_type_check CHECK (credential_type IN ('PASSWORD'))
);

CREATE TABLE auth_verification_challenge (
    id varchar(64) PRIMARY KEY,
    purpose varchar(30) NOT NULL,
    account_id bigint REFERENCES auth_account(id),
    subject_hash varchar(128) NOT NULL,
    code_hash varchar(128) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'ISSUED',
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    expires_at timestamptz NOT NULL,
    resend_after timestamptz NOT NULL,
    verified_at timestamptz,
    consumed_at timestamptz,
    created_ip_hash varchar(128),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_challenge_purpose_check CHECK (purpose IN ('REGISTER', 'LOGIN', 'PASSWORD_RESET', 'PHONE_CHANGE')),
    CONSTRAINT auth_challenge_state_check CHECK (state IN ('ISSUED', 'VERIFIED', 'CONSUMED', 'EXPIRED', 'LOCKED', 'CANCELLED'))
);
CREATE INDEX auth_challenge_subject_idx ON auth_verification_challenge(subject_hash, purpose, created_at);
CREATE INDEX auth_challenge_ip_idx ON auth_verification_challenge(created_ip_hash, created_at);
CREATE INDEX auth_challenge_expiry_idx ON auth_verification_challenge(state, expires_at);

CREATE TABLE auth_password_reset_ticket (
    id varchar(64) PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES auth_account(id),
    challenge_id varchar(64) NOT NULL REFERENCES auth_verification_challenge(id),
    token_hash varchar(128) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE auth_refresh_session (
    id varchar(64) PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES auth_account(id),
    family_id varchar(64) NOT NULL,
    token_hash varchar(128) NOT NULL UNIQUE,
    client_type varchar(30) NOT NULL,
    expires_at timestamptz NOT NULL,
    rotated_at timestamptz,
    revoked_at timestamptz,
    reuse_detected boolean NOT NULL DEFAULT false,
    context_type varchar(30) NOT NULL DEFAULT 'PERSONAL',
    tenant_id bigint,
    context_version integer NOT NULL DEFAULT 1 CHECK (context_version > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at timestamptz,
    CONSTRAINT auth_refresh_client_check CHECK (client_type IN ('WEB', 'SUPPLIER_APP', 'WORKER_MINIPROGRAM', 'BROKER_MINIPROGRAM', 'OTHER')),
    CONSTRAINT auth_refresh_context_check CHECK (context_type IN ('PERSONAL', 'ORGANIZATION', 'WORKER', 'BROKER')),
    CONSTRAINT auth_refresh_context_tenant_check CHECK
        ((context_type = 'ORGANIZATION' AND tenant_id IS NOT NULL) OR (context_type <> 'ORGANIZATION' AND tenant_id IS NULL))
);
CREATE INDEX auth_refresh_account_idx ON auth_refresh_session(account_id, revoked_at, expires_at);
CREATE INDEX auth_refresh_family_idx ON auth_refresh_session(family_id, revoked_at);

CREATE TABLE auth_security_event (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    account_id bigint REFERENCES auth_account(id),
    event_type varchar(60) NOT NULL,
    outcome varchar(20) NOT NULL,
    subject_hash varchar(128),
    ip_hash varchar(128),
    request_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT auth_security_event_outcome_check CHECK (outcome IN ('SUCCESS', 'FAILED', 'DENIED'))
);
CREATE INDEX auth_security_event_account_idx ON auth_security_event(account_id, created_at);
CREATE INDEX auth_security_event_subject_idx ON auth_security_event(subject_hash, created_at);

CREATE TABLE auth_consent_record (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    account_id bigint NOT NULL REFERENCES auth_account(id),
    consent_type varchar(40) NOT NULL,
    version varchar(80) NOT NULL,
    accepted_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_hash varchar(128),
    UNIQUE (account_id, consent_type, version)
);

-- 绑定票据只允许已登录账号显式消费一次；外部身份主键仍由 auth_login_identity 唯一约束保护。
