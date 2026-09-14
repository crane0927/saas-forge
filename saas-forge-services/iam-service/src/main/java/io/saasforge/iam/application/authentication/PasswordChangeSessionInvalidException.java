package io.saasforge.iam.application.authentication;

public final class PasswordChangeSessionInvalidException extends RuntimeException {
    public static final String CODE = "PASSWORD_CHANGE_SESSION_INVALID";

    public PasswordChangeSessionInvalidException() {
        super("首次改密会话无效或已失效");
    }
}
