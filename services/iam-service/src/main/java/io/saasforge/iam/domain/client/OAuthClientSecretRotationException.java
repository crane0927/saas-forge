package io.saasforge.iam.domain.client;

/** Client Secret 常规轮换被领域生命周期拒绝。 */
public final class OAuthClientSecretRotationException extends RuntimeException {
    private final Reason reason;

    public OAuthClientSecretRotationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        CLIENT_NOT_FOUND,
        CLIENT_REVOKED,
        OVERLAP_ACTIVE
    }
}
