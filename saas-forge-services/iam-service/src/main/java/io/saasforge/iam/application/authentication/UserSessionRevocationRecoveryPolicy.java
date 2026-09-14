package io.saasforge.iam.application.authentication;

import java.time.Duration;

public final class UserSessionRevocationRecoveryPolicy {
    private final int batchSize;
    private final Duration leaseDuration;
    private final Duration retryDelay;
    private final int maximumAttempts;

    public UserSessionRevocationRecoveryPolicy(
            int batchSize, Duration leaseDuration, Duration retryDelay, int maximumAttempts) {
        if (batchSize < 1 || batchSize > 1000 || leaseDuration == null || leaseDuration.isZero()
                || leaseDuration.isNegative() || retryDelay == null || retryDelay.isZero()
                || retryDelay.isNegative() || maximumAttempts < 1) {
            throw new IllegalArgumentException("User Session Revocation 恢复策略不合法");
        }
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
        this.retryDelay = retryDelay;
        this.maximumAttempts = maximumAttempts;
    }

    public int batchSize() { return batchSize; }
    public Duration leaseDuration() { return leaseDuration; }
    public Duration retryDelay() { return retryDelay; }
    public int maximumAttempts() { return maximumAttempts; }
}
