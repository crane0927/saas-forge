package io.saasforge.entitlement.domain.quota;

public final class QuotaDefinitionNotFoundException extends RuntimeException {
    public static final String CODE = "QUOTA_DEFINITION_NOT_FOUND";

    public QuotaDefinitionNotFoundException() {
        super("Quota Definition 不存在");
    }
}
