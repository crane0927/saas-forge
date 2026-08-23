package io.saasforge.entitlement.application.bootstrap;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EntitlementBootstrapIdempotency {
    enum Operation {
        CREATE_QUOTA_DEFINITION,
        ACTIVATE_QUOTA_DEFINITION,
        CREATE_PLAN,
        ACTIVATE_PLAN
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

    record Entry(
            Operation operation,
            String fingerprint,
            UUID targetId,
            Integer responseStatus,
            QuotaDefinitionResult quotaDefinitionResult,
            PlanResult planResult) {
        public boolean completed() {
            return responseStatus != null;
        }
    }
}
