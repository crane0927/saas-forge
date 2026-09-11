-- 平台列表沿用限定 SECURITY DEFINER 查询；不赋予运行账号跨 Tenant RLS 权限。
CREATE FUNCTION list_platform_tenants(p_name TEXT, p_status TEXT, p_after UUID, p_limit INTEGER)
RETURNS TABLE (id UUID, display_name VARCHAR(200), tenant_status TEXT,
               expires_at TIMESTAMPTZ, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)
LANGUAGE SQL STABLE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT t.id, t.display_name, t.tenant_status, t.expires_at, t.created_at, t.updated_at
    FROM public.tenants t
    WHERE (p_after IS NULL OR t.id > p_after)
      AND (p_status IS NULL OR t.tenant_status = p_status)
      AND strpos(lower(t.display_name), lower(p_name)) > 0
    ORDER BY t.id
    LIMIT LEAST(GREATEST(p_limit, 1), 101)
$$;
REVOKE ALL ON FUNCTION list_platform_tenants(TEXT, TEXT, UUID, INTEGER) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION list_platform_tenants(TEXT, TEXT, UUID, INTEGER) TO tenant_access_app;
