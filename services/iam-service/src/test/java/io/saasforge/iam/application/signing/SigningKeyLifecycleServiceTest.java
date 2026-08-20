package io.saasforge.iam.application.signing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.DurableRevocation;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SigningKeyLifecycleServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");

    @Test
    void emergencyRevocationWritesRedisBeforeAtomicallySwitchingTheActiveKeyAndRevokingJtis() {
        Fixture fixture = new Fixture();

        SigningKey revoked = fixture.service.emergencyRevoke(fixture.compromised.id(), fixture.replacement.id());

        assertEquals(SigningKeyStatus.REVOKED, revoked.status());
        assertEquals(SigningKeyStatus.ACTIVE, fixture.keys.findById(fixture.replacement.id()).orElseThrow().status());
        assertEquals(Set.of(fixture.issuance.jti()), fixture.issuances.revoked);
        assertEquals(fixture.compromised.kid(), fixture.index.kid);
        assertEquals(List.of(fixture.issuance), fixture.index.issuances);
        assertEquals(NOW.plus(Duration.ofHours(8)).plusSeconds(30), fixture.index.rejectUntil);
    }

    @Test
    void redisFailureLeavesPostgresqlFactsAndTheActiveKeyUnchanged() {
        Fixture fixture = new Fixture();
        fixture.index.fail = true;

        assertThrows(RevocationIndexUnavailableException.class,
                () -> fixture.service.emergencyRevoke(fixture.compromised.id(), fixture.replacement.id()));

        assertEquals(SigningKeyStatus.ACTIVE, fixture.keys.findById(fixture.compromised.id()).orElseThrow().status());
        assertEquals(SigningKeyStatus.PUBLISHED, fixture.keys.findById(fixture.replacement.id()).orElseThrow().status());
        assertEquals(Set.of(), fixture.issuances.revoked);
    }

    private static final class Fixture {
        private final SigningKey compromised = SigningKey.restore(
                UUID.randomUUID(), "compromised", "kms/compromised", "n1", "AQAB", SigningKeyStatus.ACTIVE,
                Duration.ofHours(8), NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(55)),
                null, null, null, null);
        private final SigningKey replacement = SigningKey.restore(
                UUID.randomUUID(), "replacement", "kms/replacement", "n2", "AQAB", SigningKeyStatus.PUBLISHED,
                Duration.ZERO, NOW.minus(Duration.ofMinutes(10)), null, null, null, null, null);
        private final AccessTokenIssuance issuance = new AccessTokenIssuance(
                UUID.fromString("0198c421-12d0-7000-8000-000000000001"), UUID.randomUUID(), UUID.randomUUID(),
                null, null, compromised.kid(), NOW.minusSeconds(60), NOW.plus(Duration.ofMinutes(15)));
        private final InMemorySigningKeys keys = new InMemorySigningKeys(compromised, replacement);
        private final InMemoryIssuances issuances = new InMemoryIssuances(issuance);
        private final RecordingRevocationIndex index = new RecordingRevocationIndex();
        private final SigningKeyLifecycleService service = new SigningKeyLifecycleService(
                keys, issuances, index, new SigningKeyRevocationTransaction(keys, issuances),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class InMemorySigningKeys implements SigningKeyRepository {
        private final Map<UUID, SigningKey> values = new LinkedHashMap<>();

        private InMemorySigningKeys(SigningKey... keys) {
            for (SigningKey key : keys) {
                values.put(key.id(), key);
            }
        }

        @Override public SigningKey savePublished(SigningKey key) { throw new UnsupportedOperationException(); }
        @Override public List<SigningKey> findActiveKeys() {
            return values.values().stream().filter(key -> key.status() == SigningKeyStatus.ACTIVE).toList();
        }
        @Override public List<SigningKey> findPublishedVerificationKeys() { return List.copyOf(values.values()); }
        @Override public Optional<SigningKey> findById(UUID keyId) { return Optional.ofNullable(values.get(keyId)); }
        @Override public SigningKey activate(UUID keyId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public SigningKey prepareActiveForIssuance(Duration tokenTtl) { throw new UnsupportedOperationException(); }
        @Override public SigningKey retire(UUID keyId, Instant at) { throw new UnsupportedOperationException(); }
        @Override public SigningKey revoke(UUID keyId, Instant at) { return revoke(keyId, null, at); }

        @Override
        public SigningKey revoke(UUID keyId, UUID replacementKeyId, Instant at) {
            SigningKey target = values.get(keyId);
            SigningKey replacement = values.get(replacementKeyId).activate(at);
            SigningKey revoked = target.revoke(at);
            values.put(keyId, revoked);
            values.put(replacementKeyId, replacement);
            return revoked;
        }
    }

    private static final class InMemoryIssuances implements AccessTokenIssuanceRepository {
        private final List<AccessTokenIssuance> values;
        private final Set<UUID> revoked = new java.util.LinkedHashSet<>();

        private InMemoryIssuances(AccessTokenIssuance... values) { this.values = List.of(values); }
        @Override public void create(AccessTokenIssuance issuance) { throw new UnsupportedOperationException(); }
        @Override public Optional<AccessTokenIssuance> findByJti(UUID jti) { return Optional.empty(); }
        @Override public boolean revoke(UUID jti, Instant revokedAt, String reason) { return revoked.add(jti); }
        @Override public List<DurableRevocation> findUnexpiredRevocations(Instant at) { return List.of(); }
        @Override public List<AccessTokenIssuance> findUnexpiredByFamilyId(UUID familyId, Instant at) { return List.of(); }
        @Override public List<AccessTokenIssuance> findUnexpiredByKid(String kid, Instant at) {
            return values.stream().filter(value -> value.kid().equals(kid) && value.expiresAt().plusSeconds(30).isAfter(at)).toList();
        }
        @Override public int revokeUnexpiredByKid(String kid, Instant revokedAt, String reason) {
            int before = revoked.size();
            findUnexpiredByKid(kid, revokedAt).forEach(value -> revoked.add(value.jti()));
            return revoked.size() - before;
        }
    }

    private static final class RecordingRevocationIndex implements RevocationIndex {
        private boolean fail;
        private String kid;
        private Instant rejectUntil;
        private List<AccessTokenIssuance> issuances = new ArrayList<>();

        @Override public void revokeJti(UUID jti, Instant expiresAt, Instant at) { throw new UnsupportedOperationException(); }
        @Override public void revokeSigningKey(
                String kid, Instant rejectUntil, List<AccessTokenIssuance> issuances, Instant at) {
            if (fail) {
                throw new RevocationIndexUnavailableException();
            }
            this.kid = kid;
            this.rejectUntil = rejectUntil;
            this.issuances = List.copyOf(issuances);
        }
        @Override public void markNotReady() { throw new UnsupportedOperationException(); }
        @Override public void rebuild(List<DurableRevocation> revocations, Instant at) { throw new UnsupportedOperationException(); }
        @Override public boolean isReady() { return true; }
        @Override public boolean isJtiRevoked(UUID jti) { return false; }
        @Override public boolean isKidRevoked(String kid) { return false; }
        @Override public boolean isTokenRevoked(UUID jti, String kid) { return false; }
    }
}
