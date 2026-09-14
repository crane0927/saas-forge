package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.identity.PasswordSetupChallenge;
import io.saasforge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saasforge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PasswordSetupServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-22T06:00:00Z");
    private static final UUID IDENTITY_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4d01");
    private static final UUID CHALLENGE_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4d02");
    private static final UUID KEY_A = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4d03");
    private static final UUID KEY_B = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4d04");

    private FakeChallenges challenges;
    private FakeIdentities identities;
    private FakeOutbox outbox;
    private PasswordSetupChallengeIssuer issuer;
    private PasswordSetupService service;

    @BeforeEach
    void setUp() {
        challenges = new FakeChallenges();
        identities = new FakeIdentities();
        outbox = new FakeOutbox();
        issuer = new PasswordSetupChallengeIssuer(new java.security.SecureRandom());
        var eventFactory = new PasswordEstablishedEventFactory(
                new ObjectMapper(), new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC),
                new java.security.SecureRandom()), "test");
        service = new PasswordSetupService(
                challenges, identities, issuer, new PasswordPolicy(), password -> false,
                new PasswordVerifier(), outbox, eventFactory, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuesExactlyOneDayChallengeWithoutPersistingPlaintext() {
        PasswordSetupChallengeToken issued = service.issueChallenge(IDENTITY_ID);

        assertEquals(43, issued.value().length());
        assertEquals(NOW.plusSeconds(86_400), issued.expiresAt());
        assertEquals(issuer.digest(issued.value()), challenges.challenge.tokenDigest());
        assertFalse(challenges.challenge.toString().contains(issued.value()));
        assertFalse(issued.toString().contains(issued.value()));
    }

    @Test
    void establishesPasswordAndReplaysOnlyTheSameTokenAndKey() {
        PasswordSetupChallengeToken issued = service.issueChallenge(IDENTITY_ID);

        service.establishPassword(KEY_A, issued.value(), "Long-Enough-Password-2026", null);
        service.establishPassword(KEY_A, issued.value(), "a-different-replay-password", null);

        assertEquals(1, identities.credentials.size());
        assertEquals(1, outbox.events.size());
        assertEquals(204, challenges.challenge.completedStatus());
        assertEquals(KEY_A, challenges.challenge.idempotencyKey());
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_B, issued.value(), "Long-Enough-Password-2026", null));
    }

    @Test
    void rejectsMalformedExpiredReplacedOrCredentialBearingChallengeUniformly() {
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_A, "not-a-token", "Long-Enough-Password-2026", null));

        PasswordSetupChallengeToken issued = service.issueChallenge(IDENTITY_ID);
        challenges.challenge = challenge(issuer.digest(issued.value()), NOW.minusSeconds(86_401), null, null);
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_A, issued.value(), "Long-Enough-Password-2026", null));

        challenges.challenge = challenge(issuer.digest(issued.value()), NOW, NOW, null);
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_A, issued.value(), "Long-Enough-Password-2026", null));

        challenges.challenge = challenge(issuer.digest(issued.value()), NOW, null, null);
        identities.credentials.add(PasswordCredential.regular(
                IDENTITY_ID, new PasswordVerifier().hash("Existing-Password-2026"), NOW.minusSeconds(1))
                .identifiedBy(UUID.randomUUID()));
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_A, issued.value(), "Long-Enough-Password-2026", null));
    }

    @Test
    void appliesExistingPasswordPolicyAndCompromisedPasswordCheckBeforeMutation() {
        PasswordSetupChallengeToken issued = service.issueChallenge(IDENTITY_ID);
        assertThrows(PasswordPolicyException.class,
                () -> service.establishPassword(KEY_A, issued.value(), "short", null));
        assertEquals(0, identities.credentials.size());
        assertEquals(0, outbox.events.size());

        service = new PasswordSetupService(
                challenges, identities, issuer, new PasswordPolicy(), password -> true,
                new PasswordVerifier(), outbox,
                new PasswordEstablishedEventFactory(new ObjectMapper(),
                        new UuidV7Generator(Clock.fixed(NOW, ZoneOffset.UTC), new java.security.SecureRandom()), "test"),
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(PasswordCompromisedException.class,
                () -> service.establishPassword(KEY_A, issued.value(), "Compromised-Password-2026", null));
        assertEquals(0, identities.credentials.size());
    }

    @Test
    void refusesChallengeForMissingUuidv7OrIdentityWithCredential() {
        assertThrows(IllegalArgumentException.class, () -> service.issueChallenge(UUID.randomUUID()));
        challenges.allowIssue = false;
        assertThrows(PasswordSetupChallengeNotAllowedException.class, () -> service.issueChallenge(IDENTITY_ID));
        assertThrows(IllegalArgumentException.class,
                () -> service.establishPassword(UUID.randomUUID(), "x", "Long-Enough-Password-2026", null));
    }

    private static PasswordSetupChallenge challenge(
            Sha256Digest digest, Instant issuedAt, Instant invalidatedAt, Instant consumedAt) {
        return new PasswordSetupChallenge(
                CHALLENGE_ID, IDENTITY_ID, digest, issuedAt, issuedAt.plusSeconds(86_400), invalidatedAt,
                consumedAt, null, null, null, null);
    }

    private static final class FakeChallenges implements PasswordSetupChallengeRepository {
        private PasswordSetupChallenge challenge;
        private boolean allowIssue = true;

        @Override
        public Optional<PasswordSetupChallenge> replaceOpenChallenge(
                UUID identityId, Sha256Digest tokenDigest, Instant issuedAt, Instant expiresAt) {
            if (!allowIssue) {
                return Optional.empty();
            }
            challenge = new PasswordSetupChallenge(
                    CHALLENGE_ID, identityId, tokenDigest, issuedAt, expiresAt,
                    null, null, null, null, null, null);
            return Optional.of(challenge);
        }

        @Override
        public Optional<PasswordSetupChallenge> findByTokenDigest(Sha256Digest tokenDigest) {
            return Optional.ofNullable(challenge).filter(value -> value.tokenDigest().equals(tokenDigest));
        }

        @Override
        public Optional<PasswordSetupChallenge> lockByTokenDigest(Sha256Digest tokenDigest) {
            return Optional.ofNullable(challenge).filter(value -> value.tokenDigest().equals(tokenDigest));
        }

        @Override
        public void complete(
                UUID challengeId, UUID idempotencyKey, Sha256Digest requestFingerprint,
                UUID credentialId, Instant consumedAt) {
            challenge = new PasswordSetupChallenge(
                    challenge.id(), challenge.identityId(), challenge.tokenDigest(), challenge.issuedAt(),
                    challenge.expiresAt(), null, consumedAt, idempotencyKey,
                    requestFingerprint, credentialId, 204);
        }
    }

    private static final class FakeIdentities implements IdentityRepository {
        private final List<PasswordCredential> credentials = new ArrayList<>();

        @Override public Identity create(Identity identity) { throw new UnsupportedOperationException(); }
        @Override public Identity findOrCreate(Identity identity) { throw new UnsupportedOperationException(); }
        @Override public Optional<Identity> findByEmail(NormalizedEmail email) { return Optional.empty(); }
        @Override public Optional<Identity> findById(UUID identityId) { return Optional.empty(); }
        @Override public void lockIdentity(UUID identityId) { }

        @Override
        public PasswordCredential create(PasswordCredential credential) {
            PasswordCredential persisted = credential.identifiedBy(UUID.randomUUID());
            credentials.add(persisted);
            return persisted;
        }

        @Override
        public Optional<PasswordCredential> createFirstPassword(PasswordCredential credential) {
            if (!credentials.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(create(credential));
        }

        @Override public PasswordCredential replaceInitialPassword(PasswordCredential initial, PasswordCredential password) {
            throw new UnsupportedOperationException();
        }
        @Override public void invalidate(UUID credentialId, Instant invalidatedAt) { throw new UnsupportedOperationException(); }
        @Override public List<PasswordCredential> lockCredentials(UUID identityId) { return List.copyOf(credentials); }
        @Override public List<PasswordCredential> findCredentials(UUID identityId) { return List.copyOf(credentials); }
        @Override public Optional<PasswordCredential> findCredential(UUID credentialId) { return Optional.empty(); }
    }

    private static final class FakeOutbox implements OutboxEventRepository {
        private final List<OutboxEvent> events = new ArrayList<>();
        @Override public void append(OutboxEvent event) { events.add(event); }
        @Override public Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant until) {
            throw new UnsupportedOperationException();
        }
        @Override public void markPublished(ClaimedOutboxEvent event, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void releaseAfterFailure(ClaimedOutboxEvent event, Instant at, String summary) {
            throw new UnsupportedOperationException();
        }
    }
}
