package io.saasforge.audit.infrastructure.messaging;

import io.saasforge.audit.application.AuditConsumerFailurePolicy;
import io.saasforge.audit.application.AuditIsolationReplayRequestOutcome;
import io.saasforge.audit.application.AuditIsolationReplayService;
import io.saasforge.audit.application.ClaimedAuditIsolationReplay;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

public class AuditIsolationReplayPublisher {
    private final AuditIsolationReplayService replays;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AuditConsumerFailurePolicy policy;
    private final Clock clock;
    private final Duration leaseDuration;
    private final String claimant;

    public AuditIsolationReplayPublisher(
            AuditIsolationReplayService replays,
            KafkaTemplate<String, String> kafkaTemplate,
            AuditConsumerFailurePolicy policy,
            Clock clock,
            Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Audit Isolation Replay 租约必须为正数");
        }
        this.replays = replays;
        this.kafkaTemplate = kafkaTemplate;
        this.policy = policy;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
        this.claimant = ManagementFactory.getRuntimeMXBean().getName() + ":" + UUID.randomUUID();
    }

    public AuditIsolationReplayRequestOutcome replay(UUID isolationId) {
        AuditIsolationReplayRequestOutcome outcome = replays.request(isolationId);
        if (outcome == AuditIsolationReplayRequestOutcome.ALREADY_RESOLVED) {
            return outcome;
        }
        var now = clock.instant();
        replays.claim(isolationId, claimant, now, now.plus(leaseDuration)).ifPresent(this::publish);
        return outcome;
    }

    private void publish(ClaimedAuditIsolationReplay replay) {
        try {
            // 安全快照按原字节语义发送，不经解析、补字段或重新序列化。
            kafkaTemplate.send(replay.topic(), replay.orderingKey(), replay.eventSnapshot())
                    .get(10, TimeUnit.SECONDS);
            replays.markSucceeded(replay, clock.instant());
        } catch (Exception exception) {
            Duration delay = policy.retryDelayAfterFailure(
                    Math.min(replay.attemptCount(), policy.maximumAttempts() - 1));
            replays.releaseAfterFailure(
                    replay, clock.instant().plus(delay), rootType(exception));
            throw new AuditIsolationReplayPublishException("Audit Isolation Replay 发布失败", exception);
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
