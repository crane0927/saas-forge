CREATE TABLE tenants (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    display_name VARCHAR(200) NOT NULL,
    tenant_status TEXT NOT NULL,
    expires_at TIMESTAMPTZ,
    CONSTRAINT ck_tenants_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_tenants_display_name CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 200),
    CONSTRAINT ck_tenants_status CHECK (tenant_status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED'))
);

CREATE TABLE memberships (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    identity_id UUID NOT NULL,
    membership_status TEXT NOT NULL,
    CONSTRAINT ck_memberships_uuidv7 CHECK (uuid_extract_version(id) = 7),
    CONSTRAINT ck_memberships_identity_uuidv7 CHECK (uuid_extract_version(identity_id) = 7),
    CONSTRAINT ck_memberships_status CHECK (membership_status IN ('ENABLED', 'DISABLED')),
    CONSTRAINT uq_memberships_tenant_identity UNIQUE (tenant_id, identity_id)
);

CREATE INDEX ix_memberships_accessible_identity
    ON memberships (identity_id, tenant_id, id)
    WHERE membership_status = 'ENABLED';

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;

CREATE POLICY tenants_runtime_access ON tenants
    FOR ALL TO tenant_access_app
    USING (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenants_migration_access ON tenants
    FOR ALL TO tenant_access_migrator
    USING (true)
    WITH CHECK (true);

CREATE POLICY memberships_runtime_access ON memberships
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY memberships_migration_access ON memberships
    FOR ALL TO tenant_access_migrator
    USING (true)
    WITH CHECK (true);

CREATE FUNCTION list_accessible_memberships(p_identity_id UUID)
RETURNS TABLE (membership_id UUID, tenant_id UUID, tenant_display_name VARCHAR(200))
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT membership.id, tenant.id, tenant.display_name
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    WHERE membership.identity_id = p_identity_id
      AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
    ORDER BY tenant.display_name COLLATE "C", membership.id
    LIMIT 101
$$;

REVOKE ALL ON FUNCTION list_accessible_memberships(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_accessible_memberships(UUID) TO tenant_access_app;

GRANT SELECT, INSERT, UPDATE ON tenants TO tenant_access_app;
GRANT SELECT, INSERT, UPDATE ON memberships TO tenant_access_app;
