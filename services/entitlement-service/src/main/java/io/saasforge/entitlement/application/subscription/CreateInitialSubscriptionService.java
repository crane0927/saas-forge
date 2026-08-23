package io.saasforge.entitlement.application.subscription;

import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapIdempotency;
import io.saasforge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyInvalidException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saasforge.entitlement.application.bootstrap.IdempotencyRequestInProgressException;
import io.saasforge.entitlement.application.bootstrap.UuidV7Generator;
import io.saasforge.entitlement.domain.outbox.OutboxEventRepository;
import io.saasforge.entitlement.domain.plan.PlanNotActiveException;
import io.saasforge.entitlement.domain.plan.PlanNotFoundException;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.plan.PlanStatus;
import io.saasforge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
import io.saasforge.entitlement.domain.subscription.Subscription;
import io.saasforge.entitlement.domain.subscription.SubscriptionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class CreateInitialSubscriptionService {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final PlanRepository plans;
    private final SubscriptionRepository subscriptions;
    private final TenantEligibilityGateway tenantEligibility;
    private final EntitlementBootstrapIdempotency idempotency;
    private final OutboxEventRepository outboxEvents;
    private final EntitlementEventFactory eventFactory;
    private final UuidV7Generator ids;
    private final Clock clock;

    public CreateInitialSubscriptionService(
            PlanRepository plans,
            SubscriptionRepository subscriptions,
            TenantEligibilityGateway tenantEligibility,
            EntitlementBootstrapIdempotency idempotency,
            OutboxEventRepository outboxEvents,
            EntitlementEventFactory eventFactory,
            UuidV7Generator ids,
            Clock clock) {
        this.plans = plans;
        this.subscriptions = subscriptions;
        this.tenantEligibility = tenantEligibility;
        this.idempotency = idempotency;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.ids = ids;
        this.clock = clock;
    }

    /** Subscription、稳定幂等响应和 subscription.created Outbox 共享本地事务。 */
    @Transactional
    public InitialSubscriptionResult create(
            UUID actorIdentityId,
            UUID idempotencyKey,
            UUID tenantId,
            UUID planId,
            Instant endsAt,
            String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(tenantId, "Tenant ID");
        requireUuidV7(planId, "Plan ID");
        if (idempotencyKey == null || idempotencyKey.version() != 7) {
            throw new IdempotencyKeyInvalidException();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        if (endsAt != null && !endsAt.isAfter(now)) {
            throw new IllegalArgumentException("Subscription endsAt 必须晚于当前时间");
        }
        UUID subscriptionId = ids.next();
        String fingerprint = fingerprint(tenantId, planId, endsAt);
        idempotency.deleteExpired(actorIdentityId, idempotencyKey, now);
        if (!idempotency.claim(
                actorIdentityId, idempotencyKey,
                EntitlementBootstrapIdempotency.Operation.CREATE_INITIAL_SUBSCRIPTION,
                fingerprint, subscriptionId, now.plus(IDEMPOTENCY_RETENTION))) {
            EntitlementBootstrapIdempotency.Entry existing = idempotency.find(actorIdentityId, idempotencyKey)
                    .orElseThrow(IdempotencyRequestInProgressException::new);
            if (existing.operation() != EntitlementBootstrapIdempotency.Operation.CREATE_INITIAL_SUBSCRIPTION
                    || !existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException();
            }
            if (!existing.completed()) {
                throw new IdempotencyRequestInProgressException();
            }
            if (existing.initialSubscriptionResult() == null) {
                throw new IllegalStateException("Subscription 幂等结果损坏");
            }
            return existing.initialSubscriptionResult();
        }

        var plan = plans.findById(planId).orElseThrow(PlanNotFoundException::new);
        if (plan.status() != PlanStatus.ACTIVE) {
            throw new PlanNotActiveException();
        }
        requireEligible(tenantEligibility.checkInitialSubscription(tenantId));
        subscriptions.setOperationTarget(tenantId);
        Subscription subscription = Subscription.active(subscriptionId, tenantId, planId, endsAt, now);
        if (!subscriptions.create(subscription)) {
            throw new InitialSubscriptionAlreadyExistsException();
        }
        InitialSubscriptionResult result = InitialSubscriptionResult.from(subscription);
        idempotency.completeInitialSubscription(actorIdentityId, idempotencyKey, 201, result, now);
        outboxEvents.append(eventFactory.subscription(result, actorIdentityId, now, traceId));
        return result;
    }

    private static void requireEligible(TenantEligibilityGateway.Outcome outcome) {
        switch (outcome) {
            case PENDING_ELIGIBLE -> { }
            case NOT_FOUND -> throw new TenantNotFoundException();
            case INVALID_STATE -> throw new TenantInvalidStateException();
            case EXPIRY_REACHED -> throw new TenantExpiryReachedException();
        }
    }

    private static String fingerprint(UUID tenantId, UUID planId, Instant endsAt) {
        String canonical = "POST\n/api/v1/platform/tenants/" + tenantId
                + "/subscriptions\n" + planId + "\n" + endsAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}
