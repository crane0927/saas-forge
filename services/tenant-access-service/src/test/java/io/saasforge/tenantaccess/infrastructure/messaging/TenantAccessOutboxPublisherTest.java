package io.saasforge.tenantaccess.infrastructure.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.tenantaccess.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class TenantAccessOutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-08-26T06:00:00Z");

    @Test
    void releasesFailedClaimWithBoundedRetryAndSanitizedFailure() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        ClaimedOutboxEvent event = new ClaimedOutboxEvent(
                uuidV7(1), uuidV7(2), "tenant-events", "tenant-2", "{}", "worker", 5);
        when(repository.claimNext(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(30))))
                .thenReturn(Optional.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker detail"));
        when(kafka.send(event.topic(), event.orderingKey(), event.eventSnapshot())).thenReturn(failed);
        TenantAccessOutboxPublisher publisher = new TenantAccessOutboxPublisher(
                repository, kafka, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

        publisher.publishNext();

        verify(repository).releaseAfterFailure(event, NOW.plusSeconds(32), "ExecutionException");
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("01991b28-7c00-7000-8000-" + String.format("%012x", value));
    }
}
