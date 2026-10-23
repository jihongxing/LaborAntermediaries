CREATE SEQUENCE foundation_id_seq;

CREATE TABLE foundation_token_revocation (
    token_id varchar(200) PRIMARY KEY,
    expires_at timestamptz NOT NULL
);
CREATE INDEX ON foundation_token_revocation (expires_at);

CREATE TABLE foundation_audit (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    actor_id bigint NOT NULL,
    tenant_id bigint,
    action varchar(100) NOT NULL,
    resource_type varchar(100) NOT NULL,
    resource_id varchar(200) NOT NULL,
    outcome varchar(40) NOT NULL,
    request_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ON foundation_audit (tenant_id, created_at);

CREATE TABLE foundation_idempotency (
    tenant_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    operation varchar(100) NOT NULL,
    request_key varchar(200) NOT NULL,
    request_hash varchar(64) NOT NULL,
    result_json text,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, actor_id, operation, request_key)
);

CREATE TABLE foundation_task (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL,
    actor_id bigint NOT NULL,
    task_type varchar(100) NOT NULL,
    payload text NOT NULL,
    request_id varchar(64),
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until timestamptz,
    lease_token varchar(64),
    last_error varchar(200),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT foundation_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
CREATE INDEX ON foundation_task (status, available_at);

CREATE TABLE foundation_file (
    id varchar(64) PRIMARY KEY,
    tenant_id bigint NOT NULL,
    owner_id bigint NOT NULL,
    original_name varchar(200) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ON foundation_file (tenant_id, owner_id);
