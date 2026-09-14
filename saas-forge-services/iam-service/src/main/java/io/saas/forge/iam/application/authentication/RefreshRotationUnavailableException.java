package io.saas.forge.iam.application.authentication;

public final class RefreshRotationUnavailableException extends RuntimeException {
    public static final String CODE = "REFRESH_ROTATION_UNAVAILABLE";

    public RefreshRotationUnavailableException(Throwable cause) {
        super("Refresh Rotation Lease 不可用", cause);
    }
}
