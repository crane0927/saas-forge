package io.saasforge.iam.application.authentication;

public final class RefreshSessionInvalidException extends RuntimeException {
    public static final String CODE = "REFRESH_SESSION_INVALID";

    public RefreshSessionInvalidException() {
        super("Refresh 会话无效或已失效");
    }
}
