package io.saasforge.iam.application.authentication;

import java.time.Duration;

public final class TenantContextSwitchRecoveryPolicy {
    private final Duration leaseDuration;
    private final Duration initialBackoff;
    private final Duration maximumBackoff;
    private final int maximumAttempts;

    public TenantContextSwitchRecoveryPolicy(
            Duration leaseDuration, Duration initialBackoff, Duration maximumBackoff, int maximumAttempts) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Tenant Context Switch 租约时长必须为正数");
        }
        if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("Tenant Context Switch 初始退避必须为正数");
        }
        if (maximumBackoff == null || maximumBackoff.compareTo(initialBackoff) < 0 || maximumAttempts < 1) {
            throw new IllegalArgumentException("Tenant Context Switch 恢复策略不合法");
        }
        this.leaseDuration = leaseDuration;
        this.initialBackoff = initialBackoff;
        this.maximumBackoff = maximumBackoff;
        this.maximumAttempts = maximumAttempts;
    }

    public Duration leaseDuration() {
        return leaseDuration;
    }

    public int maximumAttempts() {
        return maximumAttempts;
    }

    public Duration retryDelay(int attemptCount) {
        int exponent = Math.max(0, Math.min(attemptCount - 1, 30));
        long multiplier = 1L << exponent;
        long seconds;
        try {
            seconds = Math.multiplyExact(Math.max(1, initialBackoff.toSeconds()), multiplier);
        } catch (ArithmeticException exception) {
            seconds = maximumBackoff.toSeconds();
        }
        return Duration.ofSeconds(Math.min(Math.max(1, maximumBackoff.toSeconds()), seconds));
    }

    public boolean exhausted(int attemptCount) {
        return attemptCount >= maximumAttempts;
    }
}
