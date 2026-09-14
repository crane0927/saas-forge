package io.saasforge.tenantaccess.config;

import io.saasforge.tenantaccess.application.tenant.CreatePendingTenantService;
import io.saasforge.tenantaccess.application.tenant.TenantCreatedEventFactory;
import io.saasforge.tenantaccess.application.tenant.TenantCreationIdempotency;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class TenantCreationConfiguration {
    @Bean
    Clock tenantAccessClock() {
        return Clock.systemUTC();
    }

    @Bean
    UuidV7Generator tenantAccessUuidV7Generator(Clock clock) {
        return new UuidV7Generator(clock, new SecureRandom());
    }

    @Bean
    TenantCreatedEventFactory tenantCreatedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saasforge.tenant-access.outbox-topic}") String topic) {
        return new TenantCreatedEventFactory(objectMapper, ids, topic);
    }

    @Bean
    CreatePendingTenantService createPendingTenantService(
            TenantRepository tenants,
            TenantCreationIdempotency idempotency,
            OutboxEventRepository outboxEvents,
            TenantCreatedEventFactory eventFactory,
            UuidV7Generator ids,
            Clock clock) {
        return new CreatePendingTenantService(tenants, idempotency, outboxEvents, eventFactory, ids, clock);
    }
}
