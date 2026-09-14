package io.saasforge.entitlement.application.subscription;

public final class TenantExpiryReachedException extends RuntimeException {
    public static final String CODE = "TENANT_EXPIRY_REACHED";

    public TenantExpiryReachedException() {
        super("Tenant 已达到绝对 expiresAt");
    }
}
