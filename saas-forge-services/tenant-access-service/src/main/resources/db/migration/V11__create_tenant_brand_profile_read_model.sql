CREATE TABLE tenant_brand_profiles (
    tenant_id UUID PRIMARY KEY REFERENCES tenants (id),
    display_name VARCHAR(200) NOT NULL,
    logo_url VARCHAR(2048),
    favicon_url VARCHAR(2048),
    primary_color CHAR(7) NOT NULL,
    accent_color CHAR(7) NOT NULL,
    CONSTRAINT ck_tenant_brand_profiles_display_name
        CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 200),
    CONSTRAINT ck_tenant_brand_profiles_logo_url
        CHECK (logo_url IS NULL OR char_length(btrim(logo_url)) BETWEEN 1 AND 2048),
    CONSTRAINT ck_tenant_brand_profiles_favicon_url
        CHECK (favicon_url IS NULL OR char_length(btrim(favicon_url)) BETWEEN 1 AND 2048),
    CONSTRAINT ck_tenant_brand_profiles_primary_color
        CHECK (primary_color ~ '^#[0-9A-Fa-f]{6}$'),
    CONSTRAINT ck_tenant_brand_profiles_accent_color
        CHECK (accent_color ~ '^#[0-9A-Fa-f]{6}$')
);

ALTER TABLE tenant_brand_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_brand_profiles FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_brand_profiles_runtime_access ON tenant_brand_profiles
    FOR ALL TO tenant_access_app
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY tenant_brand_profiles_migration_access ON tenant_brand_profiles
    FOR ALL TO tenant_access_migrator
    USING (true)
    WITH CHECK (true);

CREATE FUNCTION list_accessible_membership_contexts(p_identity_id UUID)
RETURNS TABLE (
    membership_id UUID,
    tenant_id UUID,
    tenant_display_name VARCHAR(200),
    brand_display_name VARCHAR(200),
    brand_logo_url VARCHAR(2048),
    brand_favicon_url VARCHAR(2048),
    brand_primary_color CHAR(7),
    brand_accent_color CHAR(7)
)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT membership.id,
           tenant.id,
           tenant.display_name,
           brand.display_name,
           brand.logo_url,
           brand.favicon_url,
           brand.primary_color,
           brand.accent_color
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    LEFT JOIN public.tenant_brand_profiles AS brand ON brand.tenant_id = tenant.id
    WHERE membership.identity_id = p_identity_id
      AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
    ORDER BY tenant.display_name COLLATE "C", membership.id
    LIMIT 101
$$;

REVOKE ALL ON FUNCTION list_accessible_membership_contexts(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_accessible_membership_contexts(UUID) TO tenant_access_app;
GRANT SELECT ON tenant_brand_profiles TO tenant_access_app;
