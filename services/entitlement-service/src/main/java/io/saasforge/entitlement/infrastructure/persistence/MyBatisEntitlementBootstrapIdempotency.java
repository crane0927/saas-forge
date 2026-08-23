package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapIdempotency;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.application.subscription.InitialSubscriptionResult;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.EntitlementIdempotencyRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisEntitlementBootstrapIdempotency implements EntitlementBootstrapIdempotency {
    private static final String QUOTA_DEFINITION = "QUOTA_DEFINITION";
    private static final String PLAN = "PLAN";
    private static final String SUBSCRIPTION = "SUBSCRIPTION";

    private final EntitlementBootstrapMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisEntitlementBootstrapIdempotency(
            EntitlementBootstrapMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void deleteExpired(UUID callerIdentityId, UUID idempotencyKey, Instant now) {
        mapper.deleteExpiredIdempotency(
                callerIdentityId, idempotencyKey, EntitlementTime.asOffsetDateTime(now));
    }

    @Override
    public boolean claim(
            UUID callerIdentityId,
            UUID idempotencyKey,
            Operation operation,
            String fingerprint,
            UUID targetId,
            Instant expiresAt) {
        return mapper.claimIdempotency(new EntitlementIdempotencyRow(
                callerIdentityId, idempotencyKey, operation.name(), fingerprint, targetId,
                null, null, null, null, EntitlementTime.asOffsetDateTime(expiresAt))) == 1;
    }

    @Override
    public Optional<Entry> find(UUID callerIdentityId, UUID idempotencyKey) {
        return Optional.ofNullable(mapper.findIdempotency(callerIdentityId, idempotencyKey))
                .map(row -> new Entry(
                        Operation.valueOf(row.operationType()), row.requestFingerprint(), row.targetId(),
                        row.responseStatus(),
                        QUOTA_DEFINITION.equals(row.responseKind())
                                ? objectMapper.readValue(row.responseBody(), QuotaDefinitionResult.class) : null,
                        PLAN.equals(row.responseKind())
                                ? objectMapper.readValue(row.responseBody(), PlanResult.class) : null,
                        SUBSCRIPTION.equals(row.responseKind())
                                ? objectMapper.readValue(row.responseBody(), InitialSubscriptionResult.class) : null));
    }

    @Override
    public void completeQuotaDefinition(
            UUID callerIdentityId, UUID idempotencyKey, int responseStatus,
            QuotaDefinitionResult result, Instant completedAt) {
        complete(callerIdentityId, idempotencyKey, responseStatus, QUOTA_DEFINITION,
                objectMapper.writeValueAsString(result), completedAt);
    }

    @Override
    public void completePlan(
            UUID callerIdentityId, UUID idempotencyKey, int responseStatus,
            PlanResult result, Instant completedAt) {
        complete(callerIdentityId, idempotencyKey, responseStatus, PLAN,
                objectMapper.writeValueAsString(result), completedAt);
    }

    @Override
    public void completeInitialSubscription(
            UUID callerIdentityId, UUID idempotencyKey, int responseStatus,
            InitialSubscriptionResult result, Instant completedAt) {
        complete(callerIdentityId, idempotencyKey, responseStatus, SUBSCRIPTION,
                objectMapper.writeValueAsString(result), completedAt);
    }

    private void complete(
            UUID callerIdentityId,
            UUID idempotencyKey,
            int responseStatus,
            String responseKind,
            String responseBody,
            Instant completedAt) {
        EntitlementIdempotencyRow row = new EntitlementIdempotencyRow(
                callerIdentityId, idempotencyKey, null, null, null, responseStatus,
                responseKind, responseBody, EntitlementTime.asOffsetDateTime(completedAt), null);
        if (mapper.completeIdempotency(row) != 1) {
            throw new IllegalStateException("Entitlement 管理幂等结果保存失败");
        }
    }
}
