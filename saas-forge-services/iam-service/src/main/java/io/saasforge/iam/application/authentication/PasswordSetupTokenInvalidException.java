package io.saasforge.iam.application.authentication;

public final class PasswordSetupTokenInvalidException extends RuntimeException {
    public static final String CODE = "PASSWORD_SETUP_TOKEN_INVALID";

    public PasswordSetupTokenInvalidException() {
        super("Password Setup Token 无效");
    }
}
