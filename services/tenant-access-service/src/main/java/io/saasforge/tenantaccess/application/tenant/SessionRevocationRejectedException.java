package io.saasforge.tenantaccess.application.tenant;

public final class SessionRevocationRejectedException extends RuntimeException {
    public SessionRevocationRejectedException() {
        super("IAM Revocation Fence generation 不满足前置条件");
    }
}
