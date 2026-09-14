package io.saasforge.audit.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuditConsumerFailurePolicyTest {
    @Test
    void capsExponentialBackoffAndCountsTheInitialDeliveryAsAnAttempt() {
        var policy = new AuditConsumerFailurePolicy(10, Duration.ofSeconds(1), Duration.ofMinutes(1));

        assertEquals(9, policy.maximumRetries());
        assertEquals(Duration.ofSeconds(1), policy.retryDelayAfterFailure(1));
        assertEquals(Duration.ofSeconds(32), policy.retryDelayAfterFailure(6));
        assertEquals(Duration.ofMinutes(1), policy.retryDelayAfterFailure(7));
        assertEquals(Duration.ofMinutes(1), policy.retryDelayAfterFailure(9));
    }

    @Test
    void rejectsInvalidRetryConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new AuditConsumerFailurePolicy(1, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditConsumerFailurePolicy(10, Duration.ZERO, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditConsumerFailurePolicy(10, Duration.ofMinutes(2), Duration.ofMinutes(1)));
    }
}
