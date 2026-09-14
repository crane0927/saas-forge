package io.saasforge.iam.application.authentication;

public final class IdentityCredentialRecoveryRequiredException extends RuntimeException {
    public IdentityCredentialRecoveryRequiredException() {
        super("Identity 需要既有凭证恢复，不能建立 Password Setup Challenge");
    }
}
