ALTER TABLE foundation_audit
    DROP CONSTRAINT foundation_audit_hash_pair,
    ADD CONSTRAINT foundation_audit_hash_pair
        CHECK ((event_hash IS NULL AND previous_hash IS NULL)
            OR (event_hash IS NOT NULL
                AND (previous_hash IS NULL OR previous_hash ~ '^[0-9a-f]{64}$')));
