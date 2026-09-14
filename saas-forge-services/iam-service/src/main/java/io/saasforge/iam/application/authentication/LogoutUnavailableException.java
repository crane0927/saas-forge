package io.saasforge.iam.application.authentication;

public final class LogoutUnavailableException extends RuntimeException {
    public static final String CODE = "LOGOUT_UNAVAILABLE";

    public LogoutUnavailableException(Throwable cause) {
        super("登出持久化暂时不可用", cause);
    }
}
