package io.saasforge.iam.domain.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IdentityDomainTest {

    private static final String HASH = "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA";

    @Test
    void normalizesEmailAndPreservesDisplayNameOnReconstruction() {
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        Identity pending = Identity.register("  ADMIN@EXAMPLE.TEST  ", "管理员", now);
        Identity restored = Identity.restore(UUID.randomUUID(), pending.email(), pending.displayName(), pending.createdAt());

        assertEquals("admin@example.test", restored.email().value());
        assertEquals("管理员", restored.displayName());
    }

    @Test
    void initialPasswordExpiresAndInvalidationIsPermanent() {
        Instant issuedAt = Instant.parse("2026-08-20T00:00:00Z");
        PasswordCredential initial = PasswordCredential.initial(UUID.randomUUID(), Argon2idPasswordHash.of(HASH), issuedAt);

        assertTrue(initial.isValidAt(issuedAt.plus(23, ChronoUnit.HOURS)));
        assertFalse(initial.isValidAt(issuedAt.plus(24, ChronoUnit.HOURS)));

        PasswordCredential invalidated = initial.invalidate(issuedAt.plusSeconds(1));
        assertFalse(invalidated.isValidAt(issuedAt.plusSeconds(2)));
        assertThrows(IllegalStateException.class, () -> invalidated.invalidate(issuedAt.plusSeconds(3)));
    }

    @Test
    void rejectsNonApprovedArgon2idParameters() {
        assertThrows(IllegalArgumentException.class, () -> Argon2idPasswordHash.of("$argon2id$v=19$m=1024,t=1,p=1$hash"));
    }

    @Test
    void rejectsInvalidIdentityAndCredentialStateTransitions() {
        Instant issuedAt = Instant.parse("2026-08-20T00:00:00Z");
        Identity pending = Identity.register("member@example.test", null, issuedAt);

        assertThrows(IllegalArgumentException.class, () -> Identity.register("invalid", null, issuedAt));
        assertThrows(IllegalArgumentException.class, () -> Identity.register("member@example.test", " ", issuedAt));
        assertThrows(IllegalArgumentException.class,
                () -> Identity.register("member@example.test", "x".repeat(201), issuedAt));
        assertThrows(IllegalArgumentException.class, () -> pending.identifiedBy(null));

        Identity stored = pending.identifiedBy(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> stored.identifiedBy(UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordCredential.restore(null, UUID.randomUUID(), CredentialType.PASSWORD,
                        Argon2idPasswordHash.of(HASH), issuedAt, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordCredential.restore(UUID.randomUUID(), UUID.randomUUID(), CredentialType.PASSWORD,
                        Argon2idPasswordHash.of(HASH), issuedAt, issuedAt.plusSeconds(1), null));
    }

    @Test
    void rejectsCredentialDatesAndInvalidationBeforeIssue() {
        Instant issuedAt = Instant.parse("2026-08-20T00:00:00Z");
        UUID identityId = UUID.randomUUID();
        Argon2idPasswordHash hash = Argon2idPasswordHash.of(HASH);

        assertThrows(IllegalArgumentException.class,
                () -> PasswordCredential.restore(UUID.randomUUID(), identityId, CredentialType.INITIAL_PLATFORM_PASSWORD,
                        hash, issuedAt, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordCredential.restore(UUID.randomUUID(), identityId, CredentialType.INITIAL_PLATFORM_PASSWORD,
                        hash, issuedAt, issuedAt.minusSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> PasswordCredential.restore(UUID.randomUUID(), identityId, CredentialType.PASSWORD,
                        hash, issuedAt, null, issuedAt.minusSeconds(1)));

        PasswordCredential regular = PasswordCredential.regular(identityId, hash, issuedAt);
        assertThrows(IllegalArgumentException.class, () -> regular.invalidate(null));
        assertThrows(IllegalArgumentException.class, () -> regular.invalidate(issuedAt.minusSeconds(1)));
        assertThrows(IllegalStateException.class, () -> regular.identifiedBy(null));
        assertThrows(IllegalStateException.class,
                () -> regular.identifiedBy(UUID.randomUUID()).identifiedBy(UUID.randomUUID()));
    }
}
