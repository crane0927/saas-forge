package io.saasforge.entitlement.application.subscription;

public class SubscriptionRecoveryException extends RuntimeException {
    private final String code;
    public SubscriptionRecoveryException(String code) { super(code); this.code = code; }
    public String code() { return code; }
}
