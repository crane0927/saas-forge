package io.saasforge.tenantaccess.domain.tenant;

public final class TenantStateTransitionNotAllowedException extends RuntimeException {
    public static final String CODE = "TENANT_STATE_TRANSITION_NOT_ALLOWED";

    public TenantStateTransitionNotAllowedException() {
        super("Tenant 当前状态不允许该生命周期变更");
    }
}
