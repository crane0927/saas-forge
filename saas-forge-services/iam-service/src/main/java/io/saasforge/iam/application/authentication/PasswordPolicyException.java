package io.saasforge.iam.application.authentication;

public final class PasswordPolicyException extends RuntimeException {
    private final String code;

    public PasswordPolicyException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
