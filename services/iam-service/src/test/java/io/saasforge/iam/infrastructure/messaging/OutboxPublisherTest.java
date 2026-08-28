package io.saasforge.iam.infrastructure.messaging;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.saasforge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {
    private static final Instant NOW = Instant.parse("2026-08-28T06:00:00Z");

    @ParameterizedTest
    @ValueSource(strings = {
        "com.saasforge.iam.oauth-client.created.v1",
        "com.saasforge.iam.client-secret.rotated.v1",
        "com.saasforge.iam.oauth-client.revoked.v1",
        "com.saasforge.iam.client-secret.issuance-recovered.v1"
    })
    void retriesTheSameCommittedFactWithoutChangingIdentityOrOrdering(String eventType) {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        UUID eventId = uuidV7(1);
        String orderingKey = uuidV7(2).toString();
        String snapshot = "{\"id\":\"" + eventId + "\",\"type\":\"" + eventType + "\"}";
        ClaimedOutboxEvent first = new ClaimedOutboxEvent(
                eventId, "iam-events", orderingKey, snapshot, "worker", 1);
        ClaimedOutboxEvent retry = new ClaimedOutboxEvent(
                eventId, "iam-events", orderingKey, snapshot, "worker", 2);
        when(repository.claimNext(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(30))))
                .thenReturn(Optional.of(first), Optional.of(retry));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("broker detail"));
        CompletableFuture<SendResult<String, String>> succeeded = CompletableFuture.completedFuture(null);
        when(kafka.send(first.topic(), orderingKey, snapshot)).thenReturn(failed, succeeded);
        OutboxPublisher publisher = new OutboxPublisher(
                repository, kafka, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));

        publisher.publishNext();
        publisher.publishNext();

        verify(kafka, times(2)).send(first.topic(), orderingKey, snapshot);
        verify(repository).releaseAfterFailure(first, NOW.plusSeconds(2), "ExecutionException");
        verify(repository).markPublished(retry, NOW);
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("01991b28-7c00-7000-8000-" + String.format("%012x", value));
    }
}
