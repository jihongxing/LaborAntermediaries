-- 首批业务纵向切片：账号的外部身份由 JWT 提供，本迁移只保存组织、成员和需求业务事实。
CREATE TABLE business_organization (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    organization_role varchar(32) NOT NULL CHECK (organization_role IN ('EMPLOYER', 'LABOR_SUPPLIER')),
    legal_name varchar(200) NOT NULL,
    registration_id varchar(100) NOT NULL,
    authorization_reference varchar(200) NOT NULL,
    qualification_reference varchar(200),
    status varchar(32) NOT NULL CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'NEEDS_CORRECTION', 'REJECTED', 'SUSPENDED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    application_version bigint NOT NULL DEFAULT 1 CHECK (application_version > 0),
    reviewed_application_version bigint,
    created_by bigint NOT NULL CHECK (created_by > 0),
    reviewed_by bigint,
    review_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (registration_id),
    CHECK (tenant_id = id)
);
CREATE INDEX business_organization_status_idx ON business_organization (status, organization_role);

CREATE TABLE business_organization_revision (
    organization_id bigint NOT NULL REFERENCES business_organization(id),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    revision bigint NOT NULL CHECK (revision > 0),
    legal_name varchar(200) NOT NULL,
    registration_id varchar(100) NOT NULL,
    authorization_reference varchar(200) NOT NULL,
    qualification_reference varchar(200),
    created_by bigint NOT NULL CHECK (created_by > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, revision),
    CHECK (tenant_id = organization_id)
);
CREATE INDEX business_organization_revision_tenant_idx
    ON business_organization_revision (tenant_id, organization_id, revision);

CREATE TABLE business_membership (
    organization_id bigint NOT NULL REFERENCES business_organization(id),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    member_role varchar(32) NOT NULL CHECK (member_role IN ('ORGANIZATION_ADMIN', 'DEMAND_EDITOR', 'DEMAND_PUBLISHER')),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by bigint NOT NULL CHECK (created_by > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, actor_id),
    CHECK (tenant_id = organization_id)
);
CREATE INDEX business_membership_actor_idx ON business_membership (actor_id, status);

CREATE TABLE business_membership_request (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    organization_id bigint NOT NULL REFERENCES business_organization(id),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    requested_role varchar(32) NOT NULL CHECK (requested_role IN ('DEMAND_EDITOR', 'DEMAND_PUBLISHER')),
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    reviewed_by bigint,
    review_reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (tenant_id = organization_id)
);
CREATE UNIQUE INDEX business_membership_request_pending_idx
    ON business_membership_request (tenant_id, organization_id, actor_id) WHERE status = 'PENDING';
CREATE INDEX business_membership_request_org_idx ON business_membership_request (organization_id, status);

CREATE TABLE business_demand (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    organization_id bigint NOT NULL REFERENCES business_organization(id),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    status varchar(32) NOT NULL CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'NEEDS_CORRECTION', 'REJECTED', 'APPROVED_PENDING_PUBLISH', 'OPEN_FOR_BID', 'SUSPENDED', 'CLOSED')),
    current_revision bigint NOT NULL DEFAULT 1 CHECK (current_revision > 0),
    created_by bigint NOT NULL CHECK (created_by > 0),
    submitted_by bigint,
    reviewed_by bigint,
    reviewed_revision bigint,
    review_reason varchar(1000),
    published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (id, tenant_id),
    CHECK (tenant_id = organization_id)
);
CREATE INDEX business_demand_org_idx ON business_demand (organization_id, status, updated_at);
CREATE INDEX business_demand_open_idx ON business_demand (status, updated_at) WHERE status = 'OPEN_FOR_BID';

CREATE TABLE business_demand_revision (
    demand_id bigint NOT NULL REFERENCES business_demand(id),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    revision bigint NOT NULL CHECK (revision > 0),
    related_party_relation varchar(1000) NOT NULL,
    service_scope varchar(4000) NOT NULL,
    location varchar(1000) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    title varchar(200) NOT NULL,
    quantity numeric(20, 6) NOT NULL CHECK (quantity > 0),
    quantity_unit varchar(64) NOT NULL,
    target varchar(2000) NOT NULL,
    pricing_mode varchar(64) NOT NULL,
    measurement_basis varchar(1000) NOT NULL,
    settlement_cycle varchar(200) NOT NULL,
    bid_deadline timestamptz NOT NULL,
    selection_rule varchar(2000) NOT NULL,
    created_by bigint NOT NULL CHECK (created_by > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (demand_id, revision),
    FOREIGN KEY (demand_id, tenant_id) REFERENCES business_demand (id, tenant_id),
    CHECK (tenant_id > 0),
    CHECK (ends_at > starts_at),
    CHECK (bid_deadline < starts_at)
);
CREATE INDEX business_demand_revision_deadline_idx ON business_demand_revision (bid_deadline);

-- 审核记录保留每次决定所对应的申请版本，避免补正后覆盖历史结论。
CREATE TABLE business_review_decision (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL CHECK (tenant_id > 0),
    resource_type varchar(32) NOT NULL CHECK (resource_type IN ('ORGANIZATION', 'DEMAND')),
    resource_id bigint NOT NULL CHECK (resource_id > 0),
    resource_version bigint NOT NULL CHECK (resource_version > 0),
    from_status varchar(32) NOT NULL,
    decision varchar(32) NOT NULL CHECK (decision IN ('APPROVE', 'NEEDS_CORRECTION', 'REJECT')),
    to_status varchar(32) NOT NULL,
    reviewer_id bigint NOT NULL CHECK (reviewer_id > 0),
    reason varchar(1000),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, resource_type, resource_id, resource_version)
);
CREATE INDEX business_review_decision_resource_idx
    ON business_review_decision (tenant_id, resource_type, resource_id, created_at);

-- 供应商读取只通过所属租户的投递快照，不跨租户查询需求源表。
CREATE TABLE business_demand_delivery (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    tenant_id bigint NOT NULL REFERENCES business_organization(id),
    source_tenant_id bigint NOT NULL REFERENCES business_organization(id),
    demand_id bigint NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    title varchar(200) NOT NULL,
    quantity numeric(20, 6) NOT NULL CHECK (quantity > 0),
    quantity_unit varchar(64) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    bid_deadline timestamptz NOT NULL,
    service_scope varchar(4000) NOT NULL,
    location varchar(1000) NOT NULL,
    target varchar(2000) NOT NULL,
    pricing_mode varchar(64) NOT NULL,
    measurement_basis varchar(1000) NOT NULL,
    settlement_cycle varchar(200) NOT NULL,
    selection_rule varchar(2000) NOT NULL,
    delivered_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (demand_id, source_tenant_id) REFERENCES business_demand (id, tenant_id),
    UNIQUE (tenant_id, demand_id, revision),
    CHECK (tenant_id <> source_tenant_id),
    CHECK (ends_at > starts_at),
    CHECK (bid_deadline < starts_at)
);
CREATE INDEX business_demand_delivery_inbox_idx
    ON business_demand_delivery (tenant_id, bid_deadline, demand_id);
