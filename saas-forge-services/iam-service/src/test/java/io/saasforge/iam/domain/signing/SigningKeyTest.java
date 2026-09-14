package io.saasforge.iam.domain.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SigningKeyTest {

    @Test
    void requiresPublicationAndRetirementWindows() {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        SigningKey published = SigningKey.publish("kid-1", "kms/v1", "modulus", "AQAB", publishedAt);

        assertThrows(IllegalStateException.class, () -> published.activate(publishedAt.plus(4, ChronoUnit.MINUTES)));

        SigningKey active = published.activate(publishedAt.plus(5, ChronoUnit.MINUTES));
        SigningKey retiring = active.beginRetirement(publishedAt.plus(6, ChronoUnit.MINUTES));
        assertThrows(IllegalStateException.class, () -> retiring.retire(publishedAt.plus(35, ChronoUnit.MINUTES)));
        assertEquals(SigningKeyStatus.RETIRED, retiring.retire(publishedAt.plus(36, ChronoUnit.MINUTES)).status());
    }

    @Test
    void maxIssuedTokenTtlOnlyIncreasesAndControlsRetirementRetention() {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        SigningKey active = SigningKey.publish("kid-long", "kms/long", "modulus", "AQAB", publishedAt)
                .activate(publishedAt.plus(5, ChronoUnit.MINUTES));

        SigningKey raised = active.recordIssuedTokenTtl(Duration.ofHours(8));
        SigningKey unchanged = raised.recordIssuedTokenTtl(Duration.ofMinutes(15));
        SigningKey retiring = unchanged.beginRetirement(publishedAt.plus(6, ChronoUnit.MINUTES));

        assertSame(raised, unchanged);
        assertEquals(Duration.ofHours(8), retiring.maxIssuedTokenTtl());
        assertEquals(publishedAt.plus(6, ChronoUnit.MINUTES), retiring.retiringAt());
        assertEquals(retiring.retiringAt().plus(Duration.ofHours(8)).plusSeconds(30), retiring.retireAfter());
        assertThrows(IllegalStateException.class,
                () -> retiring.retire(retiring.retireAfter().minusSeconds(1)));
        assertEquals(SigningKeyStatus.RETIRED, retiring.retire(retiring.retireAfter()).status());
    }

    @Test
    void revokedKeyCannotReturnToSigningLifecycle() {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        SigningKey revoked = SigningKey.publish("kid-2", "kms/v2", "modulus", "AQAB", publishedAt)
                .revoke(publishedAt.plusSeconds(1));

        assertThrows(IllegalStateException.class, () -> revoked.activate(publishedAt.plus(6, ChronoUnit.MINUTES)));
    }

    @Test
    void rejectsInvalidSigningKeyTransitionsAndMakesRevocationIdempotent() {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        SigningKey pending = SigningKey.publish("kid-3", "kms/v3", "modulus", "AQAB", publishedAt);

        assertThrows(IllegalArgumentException.class,
                () -> SigningKey.publish(" ", "kms/v3", "modulus", "AQAB", publishedAt));
        assertThrows(IllegalArgumentException.class,
                () -> SigningKey.restore(null, "kid-3", "kms/v3", "modulus", "AQAB",
                        SigningKeyStatus.PUBLISHED, publishedAt, null, null, null, null));
        assertThrows(IllegalStateException.class, () -> pending.identifiedBy(null));

        SigningKey stored = pending.identifiedBy(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> stored.identifiedBy(UUID.randomUUID()));
        assertThrows(IllegalStateException.class, () -> stored.beginRetirement(publishedAt.plusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> stored.revoke(null));

        SigningKey revoked = stored.revoke(publishedAt.plusSeconds(1));
        assertSame(revoked, revoked.revoke(publishedAt.plusSeconds(2)));
    }
}
