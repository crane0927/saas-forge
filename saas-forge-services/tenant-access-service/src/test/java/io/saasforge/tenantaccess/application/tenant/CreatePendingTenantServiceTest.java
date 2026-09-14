package io.saasforge.tenantaccess.application.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.tenantaccess.domain.outbox.OutboxEvent;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantExpiryInvalidException;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
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

class CreatePendingTenantServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T01:00:00Z");
    private static final UUID ACTOR = uuidV7(1);
    private static final UUID KEY = uuidV7(2);

    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final InMemoryIdempotency idempotency = new InMemoryIdempotency();
    private final List<OutboxEvent> events = new ArrayList<>();
    private CreatePendingTenantService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UuidV7Generator ids = new UuidV7Generator(clock, new SecureRandom());
        OutboxEventRepository outbox = events::add;
        service = new CreatePendingTenantService(
                tenants,
                idempotency,
                outbox,
                new TenantCreatedEventFactory(new ObjectMapper(), ids, "saasforge.test.tenant-access-service.events"),
                ids,
                clock);
    }

    @Test
    void createsPendingTenantWithAuthoritativeTargetStableResponseAndOutbox() {
        TenantCreationResult created = service.create(
                ACTOR, KEY, "Acme", NOW.plusSeconds(3600), "11111111111111111111111111111111");
        TenantCreationResult replayed = service.create(
                ACTOR, KEY, "Acme", NOW.plusSeconds(3600), "11111111111111111111111111111111");

        assertSame(created, replayed);
        assertEquals(7, created.id().version());
        assertEquals("PENDING", created.status().name());
        assertEquals(created.id(), tenants.operationTarget);
        assertEquals(List.of(created.id()), tenants.created.stream().map(Tenant::id).toList());
        assertEquals(1, events.size());
        assertEquals(created.id(), events.get(0).tenantId());
        assertTrue(events.get(0).eventSnapshot().contains("com.saasforge.tenant.created.v1"));
    }

    @Test
    void createsTenantWithoutExpiryForPublishedV1RequestCompatibility() {
        TenantCreationResult created = service.create(ACTOR, KEY, "No Expiry", null, null);
        TenantCreationResult replayed = service.create(ACTOR, KEY, "No Expiry", null, null);

        assertSame(created, replayed);
        assertNull(created.expiresAt());
        assertNull(tenants.created.get(0).expiresAt());
    }

    @Test
    void rejectsFingerprintConflictAndExpiredAbsoluteDeadlineWithoutSideEffects() {
        service.create(ACTOR, KEY, "Acme", NOW.plusSeconds(3600), null);

        assertThrows(IdempotencyKeyReusedException.class,
                () -> service.create(ACTOR, KEY, "Other", NOW.plusSeconds(3600), null));
        assertThrows(TenantExpiryInvalidException.class,
                () -> service.create(ACTOR, uuidV7(3), "Late", NOW, null));
        assertEquals(1, tenants.created.size());
        assertEquals(1, events.size());
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private static final class InMemoryTenantRepository implements TenantRepository {
        private UUID operationTarget;
        private final List<Tenant> created = new ArrayList<>();

        @Override
        public void setOperationTarget(UUID tenantId) {
            operationTarget = tenantId;
        }

        @Override
        public void create(Tenant tenant) {
            created.add(tenant);
        }

        @Override
        public Optional<Tenant> findById(UUID tenantId) {
            return created.stream().filter(tenant -> tenant.id().equals(tenantId)).findFirst();
        }
    }

    private static final class InMemoryIdempotency implements TenantCreationIdempotency {
        private final Map<String, Entry> entries = new HashMap<>();

        @Override
        public void deleteExpired(UUID callerIdentityId, UUID idempotencyKey, Instant now) {
        }

        @Override
        public boolean claim(
                UUID callerIdentityId, UUID idempotencyKey, String fingerprint, UUID tenantId, Instant expiresAt) {
            return entries.putIfAbsent(key(callerIdentityId, idempotencyKey),
                    new Entry(fingerprint, tenantId, null)) == null;
        }

        @Override
        public Optional<Entry> find(UUID callerIdentityId, UUID idempotencyKey) {
            return Optional.ofNullable(entries.get(key(callerIdentityId, idempotencyKey)));
        }

        @Override
        public void complete(
                UUID callerIdentityId, UUID idempotencyKey, TenantCreationResult result, Instant completedAt) {
            Entry current = entries.get(key(callerIdentityId, idempotencyKey));
            entries.put(key(callerIdentityId, idempotencyKey),
                    new Entry(current.fingerprint(), current.tenantId(), result));
        }

        private static String key(UUID callerIdentityId, UUID idempotencyKey) {
            return callerIdentityId + ":" + idempotencyKey;
        }
    }
}
