package io.saasforge.tenantaccess.application.tenant;

public final class IdempotencyKeyInvalidException extends RuntimeException {
    public static final String CODE = "IDEMPOTENCY_KEY_INVALID";

    public IdempotencyKeyInvalidException() {
        super("Idempotency-Key 必须是 UUIDv7");
    }
}
