package io.saasforge.iam.domain.session;

public record TenantContextSwitchClaim(Status status, TenantContextSwitchWorkflow workflow) {
    public enum Status {
        CREATED,
        REPLAY,
        TARGET_CONFLICT,
        FAMILY_IN_PROGRESS,
        FAMILY_CONTEXT_CHANGED
    }
}
