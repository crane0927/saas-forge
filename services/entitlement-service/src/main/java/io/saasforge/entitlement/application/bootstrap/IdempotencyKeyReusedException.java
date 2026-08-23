package io.saasforge.entitlement.application.bootstrap;

public final class IdempotencyKeyReusedException extends RuntimeException {
    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyKeyReusedException() {
        super("Idempotency-Key 已用于其他请求");
    }
}
