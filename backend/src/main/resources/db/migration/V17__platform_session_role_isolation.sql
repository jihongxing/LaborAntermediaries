ALTER TABLE auth_refresh_session
    ADD COLUMN platform_role varchar(48) REFERENCES platform_authorization_role(role_code);

ALTER TABLE auth_refresh_session
    DROP CONSTRAINT auth_refresh_context_check,
    ADD CONSTRAINT auth_refresh_context_check
        CHECK (context_type IN ('PERSONAL', 'ORGANIZATION', 'WORKER', 'BROKER', 'PLATFORM')),
    ADD CONSTRAINT auth_refresh_platform_role_check
        CHECK ((context_type = 'PLATFORM' AND platform_role IS NOT NULL)
            OR (context_type <> 'PLATFORM' AND platform_role IS NULL));

CREATE FUNCTION platform_guard_membership_role_separation() RETURNS trigger AS $$
DECLARE
    account_status varchar(40);
BEGIN
    IF NEW.status <> 'ACTIVE' THEN
        RETURN NEW;
    END IF;

    SELECT status INTO account_status FROM auth_account WHERE id = NEW.actor_id FOR UPDATE;
    IF account_status IS NULL OR account_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'active membership requires an active account' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM platform_authorization_grant
        WHERE account_id = NEW.actor_id AND role_code IN ('AUTHORIZATION_ADMIN', 'INDEPENDENT_AUDITOR')
          AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
    ) THEN
        RAISE EXCEPTION 'platform authorization role cannot coexist with active organization membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER business_membership_platform_role_separation
    BEFORE INSERT OR UPDATE OF actor_id, status ON business_membership
    FOR EACH ROW EXECUTE FUNCTION platform_guard_membership_role_separation();

CREATE FUNCTION platform_guard_grant_role_separation() RETURNS trigger AS $$
DECLARE
    account_status varchar(40);
BEGIN
    SELECT status INTO account_status FROM auth_account WHERE id = NEW.account_id FOR UPDATE;
    IF account_status IS NULL OR account_status <> 'ACTIVE' THEN
        RAISE EXCEPTION 'platform authorization requires an active account' USING ERRCODE = '23514';
    END IF;
    IF NEW.role_code IN ('AUTHORIZATION_ADMIN', 'INDEPENDENT_AUDITOR') AND EXISTS (
        SELECT 1 FROM business_membership
        WHERE actor_id = NEW.account_id AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'platform authorization role cannot coexist with active organization membership'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER platform_authorization_grant_role_separation
    BEFORE INSERT ON platform_authorization_grant
    FOR EACH ROW EXECUTE FUNCTION platform_guard_grant_role_separation();
