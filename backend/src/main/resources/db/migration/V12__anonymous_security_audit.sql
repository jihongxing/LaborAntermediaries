ALTER TABLE foundation_audit
    ALTER COLUMN actor_id DROP NOT NULL,
    ADD COLUMN actor_type varchar(24) NOT NULL DEFAULT 'PERSON'
        CHECK (actor_type IN ('PERSON', 'PLATFORM', 'SERVICE', 'ANONYMOUS')),
    ADD CONSTRAINT foundation_audit_actor_consistency
        CHECK ((actor_type = 'ANONYMOUS') = (actor_id IS NULL));
