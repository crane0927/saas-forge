package io.saasforge.audit.config;

import io.saasforge.audit.application.AuditConsumerFailurePolicy;
import io.saasforge.audit.infrastructure.messaging.AuditConsumerFailureHandler;
import io.saasforge.audit.infrastructure.messaging.InvalidAuditEventException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {
    @Bean
    Clock auditClock() {
        return Clock.systemUTC();
    }

    @Bean
    AuditConsumerFailurePolicy auditConsumerFailurePolicy(
            @Value("${saasforge.audit.consumer.max-attempts:10}") int maximumAttempts,
            @Value("${saasforge.audit.consumer.initial-backoff:PT1S}") Duration initialBackoff,
            @Value("${saasforge.audit.consumer.max-backoff:PT1M}") Duration maximumBackoff) {
        return new AuditConsumerFailurePolicy(maximumAttempts, initialBackoff, maximumBackoff);
    }

    @Bean
    DefaultErrorHandler auditKafkaErrorHandler(
            AuditConsumerFailureHandler failures, AuditConsumerFailurePolicy policy) {
        var backoff = new ExponentialBackOffWithMaxRetries(policy.maximumRetries());
        backoff.setInitialInterval(policy.initialBackoff().toMillis());
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(policy.maximumBackoff().toMillis());
        var handler = new DefaultErrorHandler(failures::isolate, backoff);
        handler.addNotRetryableExceptions(InvalidAuditEventException.class);
        handler.setRetryListeners(failures::recordFailureWithoutInterruptingRetry);
        handler.setCommitRecovered(true);
        return handler;
    }
}
