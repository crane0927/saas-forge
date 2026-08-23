package io.saasforge.entitlement.application.subscription;

public final class TenantNotFoundException extends RuntimeException {
    public static final String CODE = "TENANT_NOT_FOUND";

    public TenantNotFoundException() {
        super("Tenant 不存在");
    }
}
