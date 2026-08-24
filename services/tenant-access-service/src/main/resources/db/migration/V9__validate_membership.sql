CREATE FUNCTION validate_membership(p_identity_id UUID, p_membership_id UUID)
RETURNS TABLE (tenant_id UUID)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT tenant.id
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    WHERE membership.id = p_membership_id
      AND membership.identity_id = p_identity_id
      AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
$$;

REVOKE ALL ON FUNCTION validate_membership(UUID, UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION validate_membership(UUID, UUID) TO tenant_access_app;
