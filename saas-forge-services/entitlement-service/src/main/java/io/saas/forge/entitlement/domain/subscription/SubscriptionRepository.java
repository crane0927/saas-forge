package io.saas.forge.entitlement.domain.subscription;

import java.util.UUID;

public interface SubscriptionRepository {
    void setOperationTarget(UUID tenantId);

    boolean create(Subscription subscription);
}
