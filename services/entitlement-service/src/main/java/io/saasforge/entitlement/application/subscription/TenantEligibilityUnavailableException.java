package io.saasforge.entitlement.application.subscription;

public final class TenantEligibilityUnavailableException extends RuntimeException {
    public static final String CODE = "TENANT_ELIGIBILITY_UNAVAILABLE";

    public TenantEligibilityUnavailableException(Throwable cause) {
        super("Tenant Access 首订阅资格校验不可用", cause);
    }
}
