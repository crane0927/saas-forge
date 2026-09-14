package io.saasforge.iam.infrastructure.messaging;

import io.saasforge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 以数据库租约发布已提交的不可变 Outbox 快照。 */
@Component
public class OutboxPublisher {
    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(1);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String claimant;

    public OutboxPublisher(
            OutboxEventRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            Clock clock,
            @Value("${saasforge.iam.outbox.lease-duration:PT30S}") Duration leaseDuration) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.claimant = ManagementFactory.getRuntimeMXBean().getName();
    }

    @Scheduled(fixedDelayString = "${saasforge.iam.outbox.publish-delay:PT1S}")
    public void publishNext() {
        Instant now = clock.instant();
        repository.claimNext(claimant, now, now.plus(leaseDuration)).ifPresent(this::publish);
    }

    private void publish(ClaimedOutboxEvent event) {
        try {
            kafkaTemplate.send(event.topic(), event.orderingKey(), event.eventSnapshot()).get(10, TimeUnit.SECONDS);
            repository.markPublished(event, clock.instant());
        } catch (Exception exception) {
            Duration retryDelay = Duration.ofSeconds(Math.min(
                    MAX_RETRY_DELAY.toSeconds(), 1L << Math.min(event.attemptCount(), 5)));
            repository.releaseAfterFailure(
                    event, clock.instant().plus(retryDelay), exception.getClass().getSimpleName());
        }
    }
}
