package io.saasforge.iam.application.authentication;

public final class RefreshRotationInProgressException extends RuntimeException {
    public static final String CODE = "REFRESH_ROTATION_IN_PROGRESS";
    private final long retryAfterSeconds;

    public RefreshRotationInProgressException(long retryAfterSeconds) {
        super("Refresh Token 正在由另一个请求轮换");
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
