package io.saasforge.iam.domain.client;

/** Client Secret Issuance Recovery 被持久化生命周期拒绝。 */
public final class OAuthClientSecretRecoveryException extends RuntimeException {
    private final Reason reason;

    public OAuthClientSecretRecoveryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CLIENT_NOT_FOUND,
        CLIENT_REVOKED,
        SECRET_NOT_RECOVERABLE
    }
}
