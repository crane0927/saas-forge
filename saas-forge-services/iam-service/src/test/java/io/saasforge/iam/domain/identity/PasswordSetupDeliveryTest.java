package io.saasforge.iam.domain.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordSetupDeliveryTest {
    private static final UUID CLIENT = UUID.fromString("019535d9-0000-7000-8000-000000000001");
    private static final UUID REQUEST = UUID.fromString("019535d9-0000-7000-8000-000000000002");
    private static final UUID IDENTITY = UUID.fromString("019535d9-0000-7000-8000-000000000003");
    private static final UUID CHALLENGE = UUID.fromString("019535d9-0000-7000-8000-000000000004");
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void acceptsEveryCompleteStatusShape() {
        assertFalse(delivery(PasswordSetupDeliveryStatus.PENDING, CHALLENGE, NOW.plusSeconds(1), null).completed());
        assertTrue(delivery(PasswordSetupDeliveryStatus.DELIVERED, CHALLENGE, NOW.plusSeconds(1), NOW).completed());
        assertTrue(delivery(PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW).completed());
    }

    @Test
    void rejectsEveryMissingOrNonV7IdentityField() {
        UUID v4 = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(null, REQUEST, IDENTITY,
                        PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(v4, REQUEST, IDENTITY,
                        PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(CLIENT, null, IDENTITY,
                        PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(CLIENT, v4, IDENTITY,
                        PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(CLIENT, REQUEST, null,
                        PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(CLIENT, REQUEST, v4,
                        PasswordSetupDeliveryStatus.PASSWORD_READY, null, null, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PasswordSetupDelivery(CLIENT, REQUEST, IDENTITY, null, null, null, NOW));
    }

    @Test
    void rejectsIncompleteChallengeAndCompletionShapes() {
        assertInvalid(PasswordSetupDeliveryStatus.PENDING, null, NOW.plusSeconds(1), null);
        assertInvalid(PasswordSetupDeliveryStatus.PENDING, CHALLENGE, null, null);
        assertInvalid(PasswordSetupDeliveryStatus.DELIVERED, null, null, NOW);
        assertInvalid(PasswordSetupDeliveryStatus.PASSWORD_READY, CHALLENGE, NOW.plusSeconds(1), NOW);
        assertInvalid(PasswordSetupDeliveryStatus.PASSWORD_READY, null, NOW.plusSeconds(1), NOW);
        assertInvalid(PasswordSetupDeliveryStatus.PENDING, CHALLENGE, NOW.plusSeconds(1), NOW);
        assertInvalid(PasswordSetupDeliveryStatus.DELIVERED, CHALLENGE, NOW.plusSeconds(1), null);
        assertInvalid(PasswordSetupDeliveryStatus.PENDING, UUID.randomUUID(), NOW.plusSeconds(1), null);
    }

    private static PasswordSetupDelivery delivery(
            PasswordSetupDeliveryStatus status, UUID challenge, Instant expiresAt, Instant completedAt) {
        return new PasswordSetupDelivery(CLIENT, REQUEST, IDENTITY, status, challenge, expiresAt, completedAt);
    }

    private static void assertInvalid(
            PasswordSetupDeliveryStatus status, UUID challenge, Instant expiresAt, Instant completedAt) {
        assertThrows(IllegalArgumentException.class, () -> delivery(status, challenge, expiresAt, completedAt));
    }
}
