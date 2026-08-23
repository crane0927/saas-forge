package io.saasforge.entitlement.application.bootstrap;

import io.saasforge.entitlement.application.subscription.InitialSubscriptionResult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EntitlementBootstrapIdempotency {
    enum Operation {
        CREATE_QUOTA_DEFINITION,
        ACTIVATE_QUOTA_DEFINITION,
        CREATE_PLAN,
        ACTIVATE_PLAN,
        CREATE_INITIAL_SUBSCRIPTION
    }

    void deleteExpired(UUID callerIdentityId, UUID idempotencyKey, Instant now);

    boolean claim(
            UUID callerIdentityId,
            UUID idempotencyKey,
            Operation operation,
            String fingerprint,
            UUID targetId,
            Instant expiresAt);

    Optional<Entry> find(UUID callerIdentityId, UUID idempotencyKey);

    void completeQuotaDefinition(
            UUID callerIdentityId, UUID idempotencyKey, int responseStatus,
            QuotaDefinitionResult result, Instant completedAt);

    void completePlan(
            UUID callerIdentityId, UUID idempotencyKey, int responseStatus,
            PlanResult result, Instant completedAt);

    void completeInitialSubscription(
            UUID callerIdentityId, UUID idempotencyKey, int responseStatus,
            InitialSubscriptionResult result, Instant completedAt);

    record Entry(
            Operation operation,
            String fingerprint,
            UUID targetId,
            Integer responseStatus,
            QuotaDefinitionResult quotaDefinitionResult,
            PlanResult planResult,
            InitialSubscriptionResult initialSubscriptionResult) {
        public boolean completed() {
            return responseStatus != null;
        }
    }
}
