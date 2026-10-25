-- 外部身份必须通过已登录账号显式绑定；绑定票据短时、单次且不能转移给其他账号。
CREATE TABLE auth_identity_binding_ticket (
    id varchar(64) PRIMARY KEY,
    account_id bigint NOT NULL REFERENCES auth_account(id),
    provider varchar(40) NOT NULL,
    token_hash varchar(128) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX auth_identity_binding_ticket_expiry_idx
    ON auth_identity_binding_ticket(expires_at, consumed_at);
