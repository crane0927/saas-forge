package io.saasforge.entitlement.application.subscription;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.entitlement.application.bootstrap.EntitlementBootstrapIdempotency;
import io.saasforge.entitlement.application.bootstrap.EntitlementEventFactory;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.application.bootstrap.UuidV7Generator;
import io.saasforge.entitlement.domain.outbox.OutboxEvent;
import io.saasforge.entitlement.domain.plan.Plan;
import io.saasforge.entitlement.domain.plan.PlanNotActiveException;
import io.saasforge.entitlement.domain.plan.PlanQuotaLimit;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
import io.saasforge.entitlement.domain.subscription.Subscription;
import io.saasforge.entitlement.domain.subscription.SubscriptionRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CreateInitialSubscriptionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T06:00:00Z");
    private static final UUID ACTOR = uuidV7(1);
    private static final UUID TENANT = uuidV7(2);
    private static final UUID PLAN = uuidV7(3);

    private final InMemoryPlans plans = new InMemoryPlans();
    private final InMemorySubscriptions subscriptions = new InMemorySubscriptions();
    private final InMemoryIdempotency idempotency = new InMemoryIdempotency();
    private final List<OutboxEvent> events = new ArrayList<>();
    private TenantEligibilityGateway.Outcome eligibility = TenantEligibilityGateway.Outcome.PENDING_ELIGIBLE;
    private CreateInitialSubscriptionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom());
        service = new CreateInitialSubscriptionService(
                plans, subscriptions, tenantId -> eligibility, idempotency, events::add,
                new EntitlementEventFactory(new ObjectMapper(), ids, "test-events"), ids, clock);
    }

    @Test
    void createsOneActiveSubscriptionAndReplaysStableResult() {
        plans.values.put(PLAN, activePlan());
        UUID key = uuidV7(10);
        InitialSubscriptionResult created = service.create(
                ACTOR, key, TENANT, PLAN, NOW.plusSeconds(3600), null);

        assertSame(created, service.create(ACTOR, key, TENANT, PLAN, NOW.plusSeconds(3600), null));
        assertEquals("ACTIVE", created.status().name());
        assertEquals(TENANT, subscriptions.operationTarget);
        assertEquals(1, subscriptions.values.size());
        assertEquals(1, events.size());
    }

    @Test
    void rejectsInactivePlanTenantDenialsAndASecondInitialSubscription() {
        Plan draft = Plan.draft(PLAN, "starter", "Starter", new PlanQuotaLimit(uuidV7(4), 5), NOW);
        plans.values.put(PLAN, draft);
        assertThrows(PlanNotActiveException.class,
                () -> service.create(ACTOR, uuidV7(11), TENANT, PLAN, null, null));

        plans.values.put(PLAN, draft.activate(NOW));
        eligibility = TenantEligibilityGateway.Outcome.EXPIRY_REACHED;
        assertThrows(TenantExpiryReachedException.class,
                () -> service.create(ACTOR, uuidV7(12), TENANT, PLAN, null, null));

        eligibility = TenantEligibilityGateway.Outcome.PENDING_ELIGIBLE;
        service.create(ACTOR, uuidV7(13), TENANT, PLAN, null, null);
        assertThrows(InitialSubscriptionAlreadyExistsException.class,
                () -> service.create(ACTOR, uuidV7(14), TENANT, PLAN, null, null));
        assertEquals(1, events.size());
    }

    private static Plan activePlan() {
        return Plan.draft(PLAN, "starter", "Starter", new PlanQuotaLimit(uuidV7(4), 5), NOW).activate(NOW);
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private static final class InMemoryPlans implements PlanRepository {
        private final Map<UUID, Plan> values = new HashMap<>();

        @Override public boolean create(Plan plan) { values.put(plan.id(), plan); return true; }
        @Override public Optional<Plan> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public boolean activate(UUID id, Instant updatedAt) { return false; }
    }

    private static final class InMemorySubscriptions implements SubscriptionRepository {
        private final Map<UUID, Subscription> values = new HashMap<>();
        private UUID operationTarget;

        @Override public void setOperationTarget(UUID tenantId) { operationTarget = tenantId; }
        @Override public boolean create(Subscription subscription) {
            return values.putIfAbsent(subscription.tenantId(), subscription) == null;
        }
    }

    private static final class InMemoryIdempotency implements EntitlementBootstrapIdempotency {
        private final Map<String, Entry> values = new HashMap<>();

        @Override public void deleteExpired(UUID actor, UUID key, Instant now) { }
        @Override public boolean claim(UUID actor, UUID key, Operation operation, String fingerprint,
                                       UUID targetId, Instant expiresAt) {
            return values.putIfAbsent(actor + ":" + key,
                    new Entry(operation, fingerprint, targetId, null, null, null, null)) == null;
        }
        @Override public Optional<Entry> find(UUID actor, UUID key) {
            return Optional.ofNullable(values.get(actor + ":" + key));
        }
        @Override public void completeQuotaDefinition(UUID actor, UUID key, int status,
                                                       QuotaDefinitionResult result, Instant at) { }
        @Override public void completePlan(UUID actor, UUID key, int status, PlanResult result, Instant at) { }
        @Override public void completeInitialSubscription(UUID actor, UUID key, int status,
                                                          InitialSubscriptionResult result, Instant at) {
            Entry current = values.get(actor + ":" + key);
            values.put(actor + ":" + key, new Entry(
                    current.operation(), current.fingerprint(), current.targetId(), status, null, null, result));
        }
    }
}
