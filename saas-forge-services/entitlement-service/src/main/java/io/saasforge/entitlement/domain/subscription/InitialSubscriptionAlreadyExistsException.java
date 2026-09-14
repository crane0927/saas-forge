package io.saasforge.entitlement.domain.subscription;

public final class InitialSubscriptionAlreadyExistsException extends RuntimeException {
    public static final String CODE = "SUBSCRIPTION_ALREADY_EXISTS";

    public InitialSubscriptionAlreadyExistsException() {
        super("Tenant 已创建过首 Subscription");
    }
}
