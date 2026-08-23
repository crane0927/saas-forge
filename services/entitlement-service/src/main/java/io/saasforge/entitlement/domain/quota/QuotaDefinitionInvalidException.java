package io.saasforge.entitlement.domain.quota;

public final class QuotaDefinitionInvalidException extends RuntimeException {
    public static final String CODE = "QUOTA_DEFINITION_INVALID";

    public QuotaDefinitionInvalidException(String message) {
        super(message);
    }
}
