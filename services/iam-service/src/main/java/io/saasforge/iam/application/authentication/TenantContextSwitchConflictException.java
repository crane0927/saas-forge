package io.saasforge.iam.application.authentication;

public final class TenantContextSwitchConflictException extends RuntimeException {
    public static final String IDEMPOTENCY_CODE = "TENANT_CONTEXT_SWITCH_IDEMPOTENCY_CONFLICT";
    public static final String IN_PROGRESS_CODE = "TENANT_CONTEXT_SWITCH_IN_PROGRESS";

    private final String code;

    private TenantContextSwitchConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static TenantContextSwitchConflictException idempotencyConflict() {
        return new TenantContextSwitchConflictException(
                IDEMPOTENCY_CODE, "Idempotency-Key 已绑定其他目标 Membership");
    }

    public static TenantContextSwitchConflictException inProgress() {
        return new TenantContextSwitchConflictException(
                IN_PROGRESS_CODE, "当前 Refresh Token Family 已有 Tenant Context Switch 进行中");
    }

    public String code() {
        return code;
    }
}
