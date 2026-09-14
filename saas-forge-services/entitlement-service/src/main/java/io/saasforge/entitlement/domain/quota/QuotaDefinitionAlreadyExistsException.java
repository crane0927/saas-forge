package io.saasforge.entitlement.domain.quota;

public final class QuotaDefinitionAlreadyExistsException extends RuntimeException {
    public static final String CODE = "QUOTA_DEFINITION_ALREADY_EXISTS";

    public QuotaDefinitionAlreadyExistsException() {
        super("max_users Quota Definition 已存在");
    }
}
