package io.saasforge.entitlement.application.subscription;

public final class TenantInvalidStateException extends RuntimeException {
    public static final String CODE = "TENANT_INVALID_STATE";

    public TenantInvalidStateException() {
        super("Tenant 当前状态不允许创建首 Subscription");
    }
}
