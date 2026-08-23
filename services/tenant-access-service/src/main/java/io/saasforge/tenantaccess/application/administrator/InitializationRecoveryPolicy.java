package io.saasforge.tenantaccess.application.administrator;

import java.time.Duration;

public final class InitializationRecoveryPolicy {
    private final Duration leaseDuration;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;

    public InitializationRecoveryPolicy(
            Duration leaseDuration, Duration initialBackoff, Duration maximumBackoff) {
        this(leaseDuration, initialBackoff, maximumBackoff, 10);
    }

    public InitializationRecoveryPolicy(
            Duration leaseDuration, Duration initialBackoff, Duration maximumBackoff, int maximumAttempts) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Tenant Admin 初始化租约时长必须为正数");
        }
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("Tenant Admin 初始化初始退避必须为正数");
        }
        if (maximumBackoff == null || maximumBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("Tenant Admin 初始化最大退避不得小于初始退避");
        }
        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("Tenant Admin 初始化最大自动尝试次数必须为正数");
        }
        this.leaseDuration = leaseDuration;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public Duration retryDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        long seconds;
        try {
            seconds = Math.multiplyExact(initialBackoff.toSeconds(), multiplier);
        } catch (ArithmeticException exception) {
            seconds = maximumBackoff.toSeconds();
        }
        return Duration.ofSeconds(Math.min(maximumBackoff.toSeconds(), Math.max(1, seconds)));
    }

    public boolean automaticRecoveryExhausted(int attemptCount) {
        return attemptCount >= maximumAttempts;
    }
}
