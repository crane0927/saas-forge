-- 恢复请求必须保留原始 Instant 的纳秒精度，不能用数据库时间精度重建请求指纹。
ALTER TABLE tenant_creation_recovery
    ALTER COLUMN tenant_expires_at TYPE TEXT
    USING CASE WHEN tenant_expires_at IS NULL THEN NULL
        ELSE to_char(tenant_expires_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') END;
