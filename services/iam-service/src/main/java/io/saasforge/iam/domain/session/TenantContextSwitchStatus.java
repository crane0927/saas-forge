package io.saasforge.iam.domain.session;

public enum TenantContextSwitchStatus {
    PENDING,
    NO_OP,
    CURRENT_REJECTED,
    TARGET_REJECTED,
    AWAITING_REFRESH,
    POST_SWITCH_REFRESHED,
    POST_SWITCH_REFRESH_REJECTED
}
