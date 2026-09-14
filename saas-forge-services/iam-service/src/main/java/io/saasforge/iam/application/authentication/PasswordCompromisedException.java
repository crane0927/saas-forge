package io.saasforge.iam.application.authentication;

public final class PasswordCompromisedException extends RuntimeException {
    public static final String CODE = "PASSWORD_COMPROMISED";

    public PasswordCompromisedException() {
        super("新密码命中弱口令表");
    }
}
