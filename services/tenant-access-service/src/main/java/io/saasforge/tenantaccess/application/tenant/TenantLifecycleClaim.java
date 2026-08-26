package io.saasforge.tenantaccess.application.tenant;

public record TenantLifecycleClaim(Status status, TenantLifecycleWorkflow workflow) {
    public enum Status {
        CREATED,
        RECOVERY_STARTED,
        REPLAY
    }
}
