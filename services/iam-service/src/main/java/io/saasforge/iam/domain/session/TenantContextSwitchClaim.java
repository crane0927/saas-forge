package io.saasforge.iam.domain.session;

public record TenantContextSwitchClaim(Status status, TenantContextSwitchWorkflow workflow) {
    public enum Status {
        CREATED,
        RECOVERY_CLAIMED,
        REPLAY,
        RECOVERY_EXHAUSTED,
        TARGET_CONFLICT,
        FAMILY_IN_PROGRESS,
        FAMILY_REFRESH_REQUIRED,
        FAMILY_CONTEXT_CHANGED
    }
}
