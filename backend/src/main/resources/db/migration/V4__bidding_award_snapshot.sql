-- BP-04 应标、选标与供应商确认；BP-05 只保存确认后的交接快照。
ALTER TABLE business_membership DROP CONSTRAINT IF EXISTS business_membership_member_role_check;
ALTER TABLE business_membership
    ADD CONSTRAINT business_membership_member_role_check
    CHECK (member_role IN ('ORGANIZATION_ADMIN', 'DEMAND_EDITOR', 'DEMAND_PUBLISHER', 'SUPPLIER_BIDDER', 'DEMAND_SELECTOR'));
ALTER TABLE business_membership_request DROP CONSTRAINT IF EXISTS business_membership_request_requested_role_check;
ALTER TABLE business_membership_request
    ADD CONSTRAINT business_membership_request_requested_role_check
    CHECK (requested_role IN ('DEMAND_EDITOR', 'DEMAND_PUBLISHER', 'SUPPLIER_BIDDER', 'DEMAND_SELECTOR'));

CREATE TABLE business_bid (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    demand_id bigint NOT NULL,
    employer_tenant_id bigint NOT NULL,
    supplier_tenant_id bigint NOT NULL REFERENCES business_organization(id),
    tenant_id bigint NOT NULL CHECK (tenant_id = supplier_tenant_id),
    current_revision bigint NOT NULL DEFAULT 1 CHECK (current_revision > 0),
    status varchar(24) NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWN', 'CLOSED')),
    created_by bigint NOT NULL CHECK (created_by > 0),
    withdrawn_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (demand_id, employer_tenant_id)
        REFERENCES business_demand(id, tenant_id),
    UNIQUE (id, tenant_id)
);
CREATE UNIQUE INDEX business_bid_active_supplier_idx
    ON business_bid (demand_id, supplier_tenant_id) WHERE status = 'ACTIVE';
CREATE INDEX business_bid_demand_idx ON business_bid (demand_id, status, updated_at);

CREATE TABLE business_bid_revision (
    bid_id bigint NOT NULL,
    tenant_id bigint NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    committed_quantity numeric(20, 6) NOT NULL CHECK (committed_quantity > 0),
    commitment varchar(4000) NOT NULL,
    deviation_items varchar(4000) NOT NULL,
    proposal varchar(8000) NOT NULL,
    quoted_amount numeric(20, 6) NOT NULL CHECK (quoted_amount >= 0),
    quote_currency varchar(3) NOT NULL,
    quote_unit varchar(64) NOT NULL,
    tax_rate numeric(7, 4) NOT NULL CHECK (tax_rate >= 0 AND tax_rate <= 100),
    payment_term varchar(200) NOT NULL,
    payment_arrangement varchar(2000) NOT NULL,
    created_by bigint NOT NULL CHECK (created_by > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (bid_id, revision),
    FOREIGN KEY (bid_id, tenant_id) REFERENCES business_bid(id, tenant_id)
);

CREATE TABLE business_bid_award (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    demand_id bigint NOT NULL,
    employer_tenant_id bigint NOT NULL,
    bid_id bigint NOT NULL,
    supplier_tenant_id bigint NOT NULL,
    bid_revision bigint NOT NULL CHECK (bid_revision > 0),
    awarded_quantity numeric(20, 6) NOT NULL CHECK (awarded_quantity > 0),
    confirmation_deadline timestamptz NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('PENDING_CONFIRMATION', 'CONFIRMED', 'REJECTED', 'EXPIRED')),
    decided_by bigint NOT NULL CHECK (decided_by > 0),
    decided_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at timestamptz,
    UNIQUE (id, employer_tenant_id),
    FOREIGN KEY (demand_id, employer_tenant_id) REFERENCES business_demand(id, tenant_id),
    FOREIGN KEY (bid_id, supplier_tenant_id) REFERENCES business_bid(id, tenant_id)
);
CREATE UNIQUE INDEX business_bid_award_active_bid_idx
    ON business_bid_award (demand_id, bid_id)
    WHERE status IN ('PENDING_CONFIRMATION', 'CONFIRMED');
CREATE INDEX business_bid_award_supplier_idx
    ON business_bid_award (supplier_tenant_id, status, confirmation_deadline);

CREATE TABLE business_bp05_snapshot (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    award_id bigint NOT NULL UNIQUE,
    demand_id bigint NOT NULL,
    employer_tenant_id bigint NOT NULL,
    supplier_tenant_id bigint NOT NULL,
    bid_id bigint NOT NULL,
    bid_revision bigint NOT NULL CHECK (bid_revision > 0),
    awarded_quantity numeric(20, 6) NOT NULL CHECK (awarded_quantity > 0),
    snapshot_status varchar(16) NOT NULL CHECK (snapshot_status = 'READY'),
    confirmed_by bigint NOT NULL CHECK (confirmed_by > 0),
    confirmed_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (award_id, employer_tenant_id) REFERENCES business_bid_award(id, employer_tenant_id),
    FOREIGN KEY (demand_id, employer_tenant_id) REFERENCES business_demand(id, tenant_id),
    FOREIGN KEY (bid_id, supplier_tenant_id) REFERENCES business_bid(id, tenant_id)
);
CREATE INDEX business_bp05_snapshot_party_idx
    ON business_bp05_snapshot (employer_tenant_id, supplier_tenant_id, demand_id);

CREATE TABLE business_bid_supervision_event (
    id bigint PRIMARY KEY DEFAULT nextval('foundation_id_seq'),
    demand_id bigint NOT NULL,
    employer_tenant_id bigint NOT NULL,
    action varchar(64) NOT NULL,
    evidence_reference varchar(200) NOT NULL,
    actor_id bigint NOT NULL CHECK (actor_id > 0),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (demand_id, employer_tenant_id) REFERENCES business_demand(id, tenant_id)
);
CREATE INDEX business_bid_supervision_demand_idx
    ON business_bid_supervision_event (demand_id, created_at);
