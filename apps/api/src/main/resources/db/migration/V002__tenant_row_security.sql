CREATE FUNCTION current_app_organisation_id()
RETURNS UUID
LANGUAGE SQL
STABLE
PARALLEL SAFE
RETURN nullif(current_setting('app.current_organisation_id', true), '')::UUID;

CREATE FUNCTION current_app_user_id()
RETURNS UUID
LANGUAGE SQL
STABLE
PARALLEL SAFE
RETURN nullif(current_setting('app.current_user_id', true), '')::UUID;

ALTER TABLE schools ENABLE ROW LEVEL SECURITY;
CREATE POLICY schools_tenant_isolation ON schools
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE campuses ENABLE ROW LEVEL SECURITY;
CREATE POLICY campuses_tenant_isolation ON campuses
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE academic_sessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY academic_sessions_tenant_isolation ON academic_sessions
    USING (organisation_id = current_app_organisation_id())
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
CREATE POLICY memberships_self_or_tenant ON memberships
    USING (
        user_id = current_app_user_id()
        OR organisation_id = current_app_organisation_id()
    )
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY roles_system_or_tenant ON roles
    USING (
        organisation_id IS NULL
        OR organisation_id = current_app_organisation_id()
    )
    WITH CHECK (organisation_id = current_app_organisation_id());

ALTER TABLE role_capabilities ENABLE ROW LEVEL SECURITY;
CREATE POLICY role_capabilities_tenant ON role_capabilities
    USING (
        EXISTS (
            SELECT 1
            FROM roles
            WHERE roles.id = role_capabilities.role_id
              AND (
                  roles.organisation_id IS NULL
                  OR roles.organisation_id = current_app_organisation_id()
              )
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM roles
            WHERE roles.id = role_capabilities.role_id
              AND roles.organisation_id = current_app_organisation_id()
        )
    );

ALTER TABLE membership_roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY membership_roles_tenant ON membership_roles
    USING (
        EXISTS (
            SELECT 1
            FROM memberships
            WHERE memberships.id = membership_roles.membership_id
              AND (
                  memberships.user_id = current_app_user_id()
                  OR memberships.organisation_id = current_app_organisation_id()
              )
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM memberships
            WHERE memberships.id = membership_roles.membership_id
              AND memberships.organisation_id = current_app_organisation_id()
        )
    );

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_events_tenant_read ON audit_events
    FOR SELECT
    USING (organisation_id = current_app_organisation_id());

CREATE POLICY audit_events_tenant_insert ON audit_events
    FOR INSERT
    WITH CHECK (organisation_id = current_app_organisation_id());

COMMENT ON FUNCTION current_app_organisation_id() IS
    'Returns the verified organisation scope set locally for the current database transaction.';
COMMENT ON FUNCTION current_app_user_id() IS
    'Returns the authenticated user set locally for the current database transaction.';
