package io.saasforge.entitlement.domain.quota;

public final class QuotaDefinitionTransitionException extends RuntimeException {
    public static final String CODE = "QUOTA_DEFINITION_TRANSITION_INVALID";

    public QuotaDefinitionTransitionException() {
        super("Quota Definition 只能从 DRAFT 激活");
    }
}
