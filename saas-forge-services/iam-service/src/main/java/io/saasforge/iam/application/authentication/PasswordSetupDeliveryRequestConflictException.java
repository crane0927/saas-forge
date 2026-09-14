package io.saasforge.iam.application.authentication;

public final class PasswordSetupDeliveryRequestConflictException extends RuntimeException {
    public PasswordSetupDeliveryRequestConflictException() {
        super("Password Setup 投递 requestId 已绑定其他 Identity");
    }
}
