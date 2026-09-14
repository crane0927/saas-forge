package io.saasforge.auditreplay;

import io.saasforge.audit.application.AuditConsumerFailurePolicy;
import io.saasforge.audit.application.AuditIsolationReplayRepository;
import io.saasforge.audit.application.AuditIsolationReplayService;
import io.saasforge.audit.infrastructure.messaging.AuditIsolationReplayPublisher;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

@Configuration(proxyBeanMethods = false)
class AuditIsolationReplayConfiguration {
    @Bean
    Clock auditIsolationReplayClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuditConsumerFailurePolicy auditIsolationReplayFailurePolicy(
            @Value("${saasforge.audit.consumer.max-attempts:10}") int maximumAttempts,
            @Value("${saasforge.audit.consumer.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${saasforge.audit.consumer.max-backoff:PT1M}") Duration maximumBackoff) {
        return new AuditConsumerFailurePolicy(maximumAttempts, initialBackoff, maximumBackoff);
    }

    @Bean
    AuditIsolationReplayService auditIsolationReplayService(
            AuditIsolationReplayRepository repository, Clock auditIsolationReplayClock) {
        return new AuditIsolationReplayService(repository, auditIsolationReplayClock);
    }

    @Bean
    AuditIsolationReplayPublisher auditIsolationReplayPublisher(
            AuditIsolationReplayService service,
            KafkaTemplate<String, String> kafkaTemplate,
            AuditConsumerFailurePolicy policy,
            Clock auditIsolationReplayClock,
            @Value("${saasforge.audit.replay.lease-duration:PT30S}") Duration leaseDuration) {
        return new AuditIsolationReplayPublisher(
                service, kafkaTemplate, policy, auditIsolationReplayClock, leaseDuration);
    }

    @Bean
    AuditIsolationReplayRunner auditIsolationReplayRunner(
            AuditIsolationReplayPublisher publisher,
            @Value("${saasforge.audit.replay.isolation-id}") String isolationId) {
        return new AuditIsolationReplayRunner(publisher, isolationId);
    }
}
