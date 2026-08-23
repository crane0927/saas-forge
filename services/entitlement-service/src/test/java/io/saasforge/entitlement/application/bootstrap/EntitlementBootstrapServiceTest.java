package io.saasforge.entitlement.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.entitlement.domain.outbox.OutboxEvent;
import io.saasforge.entitlement.domain.plan.Plan;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.plan.PlanTransitionException;
import io.saasforge.entitlement.domain.quota.QuotaDefinition;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionRepository;
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

class EntitlementBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T02:00:00Z");
    private static final UUID ACTOR = uuidV7(1);

    private final InMemoryQuotaDefinitions quotaDefinitions = new InMemoryQuotaDefinitions();
    private final InMemoryPlans plans = new InMemoryPlans();
    private final InMemoryIdempotency idempotency = new InMemoryIdempotency();
    private final List<OutboxEvent> events = new ArrayList<>();
    private EntitlementBootstrapService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom());
        service = new EntitlementBootstrapService(
                quotaDefinitions, plans, idempotency, events::add,
                new EntitlementEventFactory(
                        new ObjectMapper(), ids, "saasforge.test.entitlement-service.events"),
                ids, clock);
    }

    @Test
    void createsAndActivatesTheOnlyQuotaDefinitionAndSingleLimitPlanWithStableReplays() {
        UUID createQuotaKey = uuidV7(2);
        QuotaDefinitionResult quota = service.createQuotaDefinition(
                ACTOR, createQuotaKey, "max_users", "11111111111111111111111111111111");
        assertSame(quota, service.createQuotaDefinition(
                ACTOR, createQuotaKey, "max_users", "11111111111111111111111111111111"));

        QuotaDefinitionResult activeQuota = service.activateQuotaDefinition(
                ACTOR, uuidV7(3), quota.id(), null);
        PlanResult plan = service.createPlan(
                ACTOR, uuidV7(4), "starter", "Starter", quota.id(), 5, null);
        PlanResult activePlan = service.activatePlan(ACTOR, uuidV7(5), plan.id(), null);

        assertEquals("ACTIVE", activeQuota.status().name());
        assertEquals("ACTIVE", activePlan.status().name());
        assertEquals(1, activePlan.quotaLimits().size());
        assertEquals(5, activePlan.quotaLimits().get(0).limit());
        assertEquals(4, events.size());
        assertTrue(events.stream().allMatch(event -> event.eventSnapshot().contains(ACTOR.toString())));
    }

    @Test
    void rejectsFingerprintReuseAndIllegalSecondActivationWithoutAdditionalEvents() {
        UUID key = uuidV7(10);
        QuotaDefinitionResult quota = service.createQuotaDefinition(ACTOR, key, "max_users", null);

        assertThrows(IdempotencyKeyReusedException.class,
                () -> service.activateQuotaDefinition(ACTOR, key, quota.id(), null));
        service.activateQuotaDefinition(ACTOR, uuidV7(11), quota.id(), null);
        assertThrows(io.saasforge.entitlement.domain.quota.QuotaDefinitionTransitionException.class,
                () -> service.activateQuotaDefinition(ACTOR, uuidV7(12), quota.id(), null));

        PlanResult plan = service.createPlan(ACTOR, uuidV7(13), "starter", "Starter", quota.id(), 1, null);
        service.activatePlan(ACTOR, uuidV7(14), plan.id(), null);
        assertThrows(PlanTransitionException.class,
                () -> service.activatePlan(ACTOR, uuidV7(15), plan.id(), null));
        assertEquals(4, events.size());
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private static final class InMemoryQuotaDefinitions implements QuotaDefinitionRepository {
        private final Map<UUID, QuotaDefinition> values = new HashMap<>();

        @Override
        public boolean create(QuotaDefinition definition) {
            if (values.values().stream().anyMatch(existing -> existing.code().equals(definition.code()))) {
                return false;
            }
            values.put(definition.id(), definition);
            return true;
        }

        @Override
        public Optional<QuotaDefinition> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public boolean activate(UUID id, Instant updatedAt) {
            QuotaDefinition current = values.get(id);
            if (current == null || current.status() != io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus.DRAFT) {
                return false;
            }
            values.put(id, current.activate(updatedAt));
            return true;
        }
    }

    private static final class InMemoryPlans implements PlanRepository {
        private final Map<UUID, Plan> values = new HashMap<>();

        @Override
        public boolean create(Plan plan) {
            if (values.values().stream().anyMatch(existing -> existing.code().equals(plan.code()))) {
                return false;
            }
            values.put(plan.id(), plan);
            return true;
        }

        @Override
        public Optional<Plan> findById(UUID id) {
            return Optional.ofNullable(values.get(id));
        }

        @Override
        public boolean activate(UUID id, Instant updatedAt) {
            Plan current = values.get(id);
            if (current == null || current.status() != io.saasforge.entitlement.domain.plan.PlanStatus.DRAFT) {
                return false;
            }
            values.put(id, current.activate(updatedAt));
            return true;
        }
    }

    private static final class InMemoryIdempotency implements EntitlementBootstrapIdempotency {
        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public void deleteExpired(UUID callerIdentityId, UUID idempotencyKey, Instant now) {
        }

        @Override
        public boolean claim(
                UUID callerIdentityId, UUID idempotencyKey, Operation operation,
                String fingerprint, UUID targetId, Instant expiresAt) {
            return entries.putIfAbsent(key(callerIdentityId, idempotencyKey),
                    new Entry(operation, fingerprint, targetId, null, null, null)) == null;
        }

        @Override
        public Optional<Entry> find(UUID callerIdentityId, UUID idempotencyKey) {
            return Optional.ofNullable(entries.get(key(callerIdentityId, idempotencyKey)));
        }

        @Override
        public void completeQuotaDefinition(
                UUID callerIdentityId, UUID idempotencyKey, int status,
                QuotaDefinitionResult result, Instant completedAt) {
            Entry current = entries.get(key(callerIdentityId, idempotencyKey));
            entries.put(key(callerIdentityId, idempotencyKey), new Entry(
                    current.operation(), current.fingerprint(), current.targetId(), status, result, null));
        }

        @Override
        public void completePlan(
                UUID callerIdentityId, UUID idempotencyKey, int status,
                PlanResult result, Instant completedAt) {
            Entry current = entries.get(key(callerIdentityId, idempotencyKey));
            entries.put(key(callerIdentityId, idempotencyKey), new Entry(
                    current.operation(), current.fingerprint(), current.targetId(), status, null, result));
        }

        private static String key(UUID actor, UUID idempotencyKey) {
            return actor + ":" + idempotencyKey;
        }
    }
}
