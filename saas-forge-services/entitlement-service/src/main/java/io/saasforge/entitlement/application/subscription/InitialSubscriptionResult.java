package io.saasforge.entitlement.application.subscription;

import io.saasforge.entitlement.domain.subscription.Subscription;
import io.saasforge.entitlement.domain.subscription.SubscriptionStatus;
import java.time.Instant;
import java.util.UUID;

public record InitialSubscriptionResult(
        UUID id,
        UUID tenantId,
        UUID planId,
        SubscriptionStatus status,
        Instant endsAt,
        Instant createdAt) {
    public static InitialSubscriptionResult from(Subscription subscription) {
        return new InitialSubscriptionResult(
                subscription.id(), subscription.tenantId(), subscription.planId(), subscription.status(),
                subscription.endsAt(), subscription.createdAt());
    }
}
