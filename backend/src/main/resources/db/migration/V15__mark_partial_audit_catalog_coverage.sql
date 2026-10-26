ALTER TABLE foundation_audit_catalog
    DROP CONSTRAINT foundation_audit_catalog_status_check,
    ADD CONSTRAINT foundation_audit_catalog_status_check
        CHECK (status IN ('NOT_IMPLEMENTED', 'PENDING', 'PARTIAL', 'CONNECTED', 'DEGRADED'));

UPDATE foundation_audit_catalog
SET status = 'PARTIAL'
WHERE event_code IN (
    'foundation:request:failed',
    'foundation:access:denied',
    'foundation:audit:failure',
    'organization:create',
    'organization:submit',
    'organization:update',
    'organization:review',
    'membership:request',
    'membership:reject',
    'membership:approve',
    'demand:create',
    'demand:update',
    'demand:submit',
    'demand:review',
    'demand:publish',
    'demand:suspend',
    'demand:close',
    'bid:submit',
    'bid:withdraw',
    'bid:award',
    'bid:expire',
    'bid:confirm',
    'bid:reject',
    'bid:supervise',
    'file.upload',
    'file.download',
    'snapshot.append'
);
