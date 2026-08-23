package io.saasforge.entitlement.domain.quota;

public enum QuotaOperationOutcome {
    SUCCESS,
    QUOTA_DEFINITION_NOT_FOUND,
    SUBSCRIPTION_REQUIRED,
    QUOTA_EXCEEDED,
    QUOTA_RELEASE_UNDERFLOW
}
