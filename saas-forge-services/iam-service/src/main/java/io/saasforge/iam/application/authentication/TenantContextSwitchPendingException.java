package io.saasforge.iam.application.authentication;

public final class TenantContextSwitchPendingException extends RuntimeException {
    public static final String CODE = "TENANT_CONTEXT_SWITCH_PENDING";

    private final long retryAfterSeconds;

    public TenantContextSwitchPendingException(long retryAfterSeconds) {
        super("Tenant Context Switch 已持久化并等待继续处理");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
