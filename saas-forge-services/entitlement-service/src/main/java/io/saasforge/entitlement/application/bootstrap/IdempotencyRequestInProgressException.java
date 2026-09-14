package io.saasforge.entitlement.application.bootstrap;

public final class IdempotencyRequestInProgressException extends RuntimeException {
    public static final String CODE = "IDEMPOTENCY_REQUEST_IN_PROGRESS";

    public IdempotencyRequestInProgressException() {
        super("幂等请求仍在处理中");
    }
}
