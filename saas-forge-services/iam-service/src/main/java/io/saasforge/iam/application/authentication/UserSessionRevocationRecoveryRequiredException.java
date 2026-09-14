package io.saasforge.iam.application.authentication;

public final class UserSessionRevocationRecoveryRequiredException extends RuntimeException {
    public UserSessionRevocationRecoveryRequiredException() {
        super("User Session Revocation 需要显式恢复");
    }
}
