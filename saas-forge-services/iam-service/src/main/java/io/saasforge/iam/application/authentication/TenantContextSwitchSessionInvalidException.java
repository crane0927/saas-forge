package io.saasforge.iam.application.authentication;

public final class TenantContextSwitchSessionInvalidException extends RuntimeException {
    public static final String CODE = "TENANT_CONTEXT_SWITCH_SESSION_INVALID";

    public TenantContextSwitchSessionInvalidException() {
        super("Tenant Context Switch 会话无效或已失效");
    }
}
