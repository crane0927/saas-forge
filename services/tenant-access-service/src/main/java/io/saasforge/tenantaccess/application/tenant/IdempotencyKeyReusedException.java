package io.saasforge.tenantaccess.application.tenant;

public final class IdempotencyKeyReusedException extends RuntimeException {
    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key 已绑定到不同请求");
    }
}
