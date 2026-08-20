package io.saasforge.iam.domain.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RefreshTokenFamilyTest {

    @Test
    void refreshKeepsOriginalAbsoluteExpiryAndCarriesNewContext() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        UUID identityId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        RefreshTokenFamily family = RefreshTokenFamily.start(identityId, null, null, loginAt);

        RefreshTokenFamily refreshed = family.recordUse(membershipId, tenantId, loginAt.plus(29, ChronoUnit.MINUTES));

        assertEquals(loginAt.plus(8, ChronoUnit.HOURS), refreshed.absoluteExpiresAt());
        assertEquals(membershipId, refreshed.membershipId());
        assertEquals(tenantId, refreshed.tenantId());
        assertTrue(refreshed.isUsableAt(loginAt.plus(30, ChronoUnit.MINUTES)));
        assertFalse(refreshed.isUsableAt(loginAt.plus(8, ChronoUnit.HOURS)));
    }

    @Test
    void idleFamilyCannotBeUsedOrResurrected() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        RefreshTokenFamily family = RefreshTokenFamily.start(UUID.randomUUID(), null, null, loginAt);

        assertThrows(IllegalStateException.class,
                () -> family.recordUse(null, null, loginAt.plus(30, ChronoUnit.MINUTES)));
    }

    @Test
    void rejectsIncompleteContextAndRevocationCanOnlyMoveForward() {
        Instant loginAt = Instant.parse("2026-08-20T00:00:00Z");
        UUID identityId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.start(identityId, UUID.randomUUID(), null, loginAt));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.restore(null, identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, loginAt, loginAt.plusSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> RefreshTokenFamily.restore(UUID.randomUUID(), identityId, RefreshTokenFamilyPurpose.USER_PLATFORM,
                        null, null, loginAt, loginAt, null));

        RefreshTokenFamily pending = RefreshTokenFamily.start(identityId, null, null, loginAt);
        assertThrows(IllegalStateException.class, () -> pending.identifiedBy(null));
        RefreshTokenFamily stored = pending.identifiedBy(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> stored.identifiedBy(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> stored.revoke(null));

        RefreshTokenFamily revoked = stored.revoke(loginAt.plusSeconds(1));
        assertSame(revoked, revoked.revoke(loginAt.plusSeconds(2)));
        assertFalse(revoked.isUsableAt(loginAt.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> revoked.requireUsableAt(loginAt.plusSeconds(2)));
    }
}
