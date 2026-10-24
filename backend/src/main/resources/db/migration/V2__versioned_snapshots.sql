-- 资源版本由受控服务推进，不是可绕过组织授权的共享业务表。
CREATE TABLE foundation_snapshot_head (
    resource_type varchar(64) NOT NULL,
    resource_id varchar(128) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    PRIMARY KEY (resource_type, resource_id)
);

-- 历史快照只追加；业务修正应生成新版本，不覆盖旧的合作条件或履约依据。
CREATE TABLE foundation_snapshot (
    resource_type varchar(64) NOT NULL,
    resource_id varchar(128) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    schema_key varchar(100) NOT NULL,
    schema_version integer NOT NULL CHECK (schema_version > 0),
    content_json text NOT NULL CHECK (octet_length(content_json) <= 65536),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    request_id varchar(64),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (resource_type, resource_id, revision),
    FOREIGN KEY (resource_type, resource_id) REFERENCES foundation_snapshot_head(resource_type, resource_id)
);
CREATE INDEX ON foundation_snapshot (tenant_id, created_at);
