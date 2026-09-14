package io.saasforge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.saasforge.audit.application.AuditConsumerIsolation;
import io.saasforge.audit.application.AuditConsumerIsolationRepository;
import io.saasforge.audit.application.AuditConsumerIsolationService;
import io.saasforge.audit.application.AuditConsumerFailurePolicy;
import io.saasforge.audit.application.AuditProcessingFailure;
import io.saasforge.audit.application.ClaimedAuditIsolationDelivery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuditConsumerFailureHandlerTest {
    private static final String IAM_TOPIC = "saasforge.test.iam-service.events";
    private static final String TENANT_TOPIC = "saasforge.test.tenant-access-service.events";

    @Test
    void permanentFailurePersistsDigestAndSanitizedDiagnosticWithoutPayload() {
        var repository = new CapturingRepository();
        var handler = handler(repository);
        String payload = SessionStartedEventValidatorTest.event("")
                .replace("\"data\": {", "\"data\": {\"accessToken\": \"must-not-persist\",");
        var message = new ConsumerRecord<String, String>(IAM_TOPIC, 2, 17, "identity-key", payload);

        handler.recordFailure(message,
                new InvalidAuditEventException("must-not-persist"), 1);
        handler.isolate(message, new InvalidAuditEventException("must-not-persist"));

        assertEquals("InvalidAuditEventException", repository.failure.diagnostic());
        assertEquals(64, repository.failure.payloadSha256().length());
        assertEquals("PERMANENT_VALIDATION", repository.isolation.failureCategory());
        assertEquals("InvalidAuditEventException", repository.isolation.diagnostic());
        assertNull(repository.isolation.orderingKey());
        assertNull(repository.isolation.safeSnapshot());
        assertNull(repository.isolation.isolationTopic());
    }

    @Test
    void exhaustedValidEnvelopeCreatesConsumerOwnedSnapshotDeliveryWithOriginalEventId() {
        var repository = new CapturingRepository();
        var handler = handler(repository);
        String eventId = "019535d9-0001-7000-8000-000000000083";
        String payload = TenantCreatedEventValidatorTest.event(eventId, "");
        var message = new ConsumerRecord<String, String>(
                TENANT_TOPIC, 1, 18, TenantCreatedEventValidatorTest.tenantId(), payload);

        handler.recordFailure(message, new IllegalStateException("database-secret"), 10);
        handler.isolate(message, new IllegalStateException("database-secret"));

        assertEquals("TRANSIENT_PROCESSING", repository.failure.failureCategory());
        assertEquals("IllegalStateException", repository.failure.diagnostic());
        assertEquals("RETRY_EXHAUSTED", repository.isolation.failureCategory());
        assertEquals(UUID.fromString(eventId), repository.isolation.eventId());
        assertEquals(payload, repository.isolation.safeSnapshot());
        assertEquals("saasforge.test.audit-service.tenant-isolations",
                repository.isolation.isolationTopic());
    }

    private static AuditConsumerFailureHandler handler(CapturingRepository repository) {
        ObjectMapper objectMapper = new ObjectMapper();
        var iam = new IamSessionEventValidator(
                objectMapper,
                new SessionStartedEventValidator(objectMapper, IAM_TOPIC),
                new TenantContextSwitchedEventValidator(objectMapper, IAM_TOPIC));
        var tenant = new TenantAccessEventValidator(
                objectMapper, new TenantCreatedEventValidator(objectMapper, TENANT_TOPIC), TENANT_TOPIC);
        var topology = new AuditConsumerTopology(
                IAM_TOPIC, TENANT_TOPIC,
                "saasforge.test.audit-service.iam-session-isolations",
                "saasforge.test.audit-service.tenant-isolations");
        var service = new AuditConsumerIsolationService(
                repository, Clock.fixed(Instant.parse("2026-08-29T04:00:00Z"), ZoneOffset.UTC));
        return new AuditConsumerFailureHandler(
                objectMapper, iam, tenant, topology, service,
                new AuditConsumerFailurePolicy(10, java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofMinutes(1)));
    }

    private static final class CapturingRepository implements AuditConsumerIsolationRepository {
        private AuditProcessingFailure failure;
        private AuditConsumerIsolation isolation;

        @Override
        public void appendProcessingFailure(AuditProcessingFailure failure, Instant occurredAt) {
            this.failure = failure;
        }

        @Override
        public UUID isolate(AuditConsumerIsolation isolation, Instant isolatedAt) {
            this.isolation = isolation;
            return UUID.fromString("019535d9-0001-7000-8000-000000000099");
        }

        @Override
        public Optional<ClaimedAuditIsolationDelivery> claimNext(
                String claimant, Instant claimedAt, Instant claimedUntil) {
            return Optional.empty();
        }

        @Override
        public void markPublished(ClaimedAuditIsolationDelivery delivery, Instant publishedAt) {}

        @Override
        public void releaseAfterFailure(
                ClaimedAuditIsolationDelivery delivery, Instant retryAt, String diagnostic) {}
    }
}
