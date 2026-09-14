package io.saas.forge.iam.application.authentication;

public final class AuthenticationFailedException extends RuntimeException {
    public static final String CODE = "AUTHENTICATION_FAILED";

    public AuthenticationFailedException() {
        super("邮箱或密码无效");
    }
}
