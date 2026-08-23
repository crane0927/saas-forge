package io.saasforge.tenantaccess.application.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InitialSubscriptionEligibilityServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-23T06:00:00Z");

    @Test
    void derivesAllEligibilityOutcomesFromAuthoritativeTenantState() {
        InMemoryTenants tenants = new InMemoryTenants();
        InitialSubscriptionEligibilityService service = new InitialSubscriptionEligibilityService(
                tenants, Clock.fixed(NOW, ZoneOffset.UTC));
        UUID eligible = uuidV7(1);
        UUID expired = uuidV7(2);
        UUID active = uuidV7(3);
        tenants.values.put(eligible, tenant(eligible, TenantStatus.PENDING, NOW.plusSeconds(1)));
        tenants.values.put(expired, tenant(expired, TenantStatus.PENDING, NOW));
        tenants.values.put(active, tenant(active, TenantStatus.ACTIVE, NOW.plusSeconds(1)));

        assertEquals(InitialSubscriptionEligibility.PENDING_ELIGIBLE, service.check(eligible));
        assertEquals(InitialSubscriptionEligibility.EXPIRY_REACHED, service.check(expired));
        assertEquals(InitialSubscriptionEligibility.INVALID_STATE, service.check(active));
        assertEquals(InitialSubscriptionEligibility.NOT_FOUND, service.check(uuidV7(4)));
    }

    private static Tenant tenant(UUID id, TenantStatus status, Instant expiresAt) {
        return new Tenant(id, "Tenant", status, expiresAt, NOW.minusSeconds(60), NOW.minusSeconds(60));
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private static final class InMemoryTenants implements TenantRepository {
        private final Map<UUID, Tenant> values = new HashMap<>();

        @Override
        public void setOperationTarget(UUID tenantId) {
        }

        @Override
        public void create(Tenant tenant) {
            values.put(tenant.id(), tenant);
        }

        @Override
        public Optional<Tenant> findById(UUID tenantId) {
            return Optional.ofNullable(values.get(tenantId));
        }
    }
}
