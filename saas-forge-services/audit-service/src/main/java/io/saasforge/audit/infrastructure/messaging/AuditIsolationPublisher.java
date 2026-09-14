package io.saasforge.audit.infrastructure.messaging;

import io.saasforge.audit.application.AuditConsumerFailurePolicy;
import io.saasforge.audit.application.AuditConsumerIsolationService;
import io.saasforge.audit.application.ClaimedAuditIsolationDelivery;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 以可接管数据库租约持续发布已验证的隔离快照。 */
@Component
public class AuditIsolationPublisher {
    private final AuditConsumerIsolationService isolations;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AuditConsumerFailurePolicy policy;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String claimant;

    public AuditIsolationPublisher(
            AuditConsumerIsolationService isolations,
            KafkaTemplate<String, String> kafkaTemplate,
            AuditConsumerFailurePolicy policy,
            Clock clock,
            @Value("${saasforge.audit.isolation.lease-duration:PT30S}") Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Audit Isolation Delivery 租约必须为正数");
        }
        this.isolations = isolations;
        this.kafkaTemplate = kafkaTemplate;
        this.policy = policy;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.claimant = ManagementFactory.getRuntimeMXBean().getName();
    }

    @Scheduled(fixedDelayString = "${saasforge.audit.isolation.publish-delay:PT1S}")
    public void publishNext() {
        Instant now = clock.instant();
        isolations.claimNext(claimant, now, now.plus(leaseDuration)).ifPresent(this::publish);
    }

    private void publish(ClaimedAuditIsolationDelivery delivery) {
        try {
            kafkaTemplate.send(delivery.topic(), delivery.orderingKey(), delivery.eventSnapshot())
                    .get(10, TimeUnit.SECONDS);
            isolations.markPublished(delivery, clock.instant());
        } catch (Exception exception) {
            Duration delay = policy.retryDelayAfterFailure(
                    Math.min(delivery.attemptCount(), policy.maximumAttempts() - 1));
            isolations.releaseAfterFailure(
                    delivery, clock.instant().plus(delay), rootType(exception));
        }
    }

    private static String rootType(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName();
    }
}
