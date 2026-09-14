package io.saasforge.audit.application;

import java.time.Duration;

public record AuditConsumerFailurePolicy(
        int maximumAttempts, Duration initialBackoff, Duration maximumBackoff) {
    public AuditConsumerFailurePolicy {
        if (maximumAttempts < 2) {
            throw new IllegalArgumentException("Audit Consumer 最大尝试次数不能小于 2");
        }
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("Audit Consumer 初始退避必须为正数");
        }
        if (maximumBackoff == null || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Audit Consumer 最大退避不能小于初始退避");
        }
    }

    public int maximumRetries() {
        return maximumAttempts - 1;
    }

    public Duration retryDelayAfterFailure(int failedAttempt) {
        if (failedAttempt < 1 || failedAttempt >= maximumAttempts) {
            throw new IllegalArgumentException("Audit Consumer 失败次数超出可重试范围");
        }
        long multiplier = 1L << Math.min(failedAttempt - 1, 62);
        Duration delay;
        try {
            delay = initialBackoff.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            return maximumBackoff;
        }
        return delay.compareTo(maximumBackoff) > 0 ? maximumBackoff : delay;
    }
}
