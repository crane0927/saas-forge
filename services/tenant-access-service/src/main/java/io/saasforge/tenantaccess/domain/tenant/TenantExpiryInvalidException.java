package io.saasforge.tenantaccess.domain.tenant;

public final class TenantExpiryInvalidException extends RuntimeException {
    public static final String CODE = "TENANT_EXPIRY_INVALID";

    public TenantExpiryInvalidException() {
        super("expiresAt 必须晚于服务端当前时间");
    }
}
