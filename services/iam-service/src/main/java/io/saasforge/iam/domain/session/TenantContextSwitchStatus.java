package io.saasforge.iam.domain.session;

public enum TenantContextSwitchStatus {
    PENDING,
    NO_OP,
    CURRENT_REJECTED,
    TARGET_REJECTED
}
