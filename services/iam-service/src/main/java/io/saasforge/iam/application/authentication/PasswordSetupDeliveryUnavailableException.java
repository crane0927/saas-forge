package io.saasforge.iam.application.authentication;

public final class PasswordSetupDeliveryUnavailableException extends RuntimeException {
    public PasswordSetupDeliveryUnavailableException() {
        super("Password Setup 邮件投递当前不可用");
    }

    public PasswordSetupDeliveryUnavailableException(Throwable cause) {
        super("Password Setup 邮件投递当前不可用", cause);
    }
}
