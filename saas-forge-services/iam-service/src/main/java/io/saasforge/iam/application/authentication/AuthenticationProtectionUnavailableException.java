package io.saasforge.iam.application.authentication;

public final class AuthenticationProtectionUnavailableException extends RuntimeException {
    public static final String CODE = "AUTHENTICATION_PROTECTION_UNAVAILABLE";

    public AuthenticationProtectionUnavailableException(Throwable cause) {
        super("登录保护服务暂时不可用", cause);
    }
}
