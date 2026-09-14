package io.saasforge.iam.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.domain.session.RefreshTokenConsumption;
import io.saasforge.iam.domain.session.RefreshTokenFamilyContextChange;
import io.saasforge.iam.domain.session.RefreshRotation;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.RefreshTokenMapper;
import io.saasforge.iam.infrastructure.persistence.record.RefreshTokenFamilyRow;
import io.saasforge.iam.infrastructure.persistence.record.RefreshTokenRow;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MyBatisRefreshTokenFamilyRepositoryTest {

    @Test
    void tokenLookupsRespectMissingConsumedExpiredAndSelectionStates() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        Sha256Digest digest = digest((byte) 1);

        assertTrue(repository.findUsableByTokenDigest(digest, lastUsedAt).isEmpty());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        mapper.originalToken.setConsumedAt(at(lastUsedAt));
        assertTrue(repository.findUsableByTokenDigest(digest, lastUsedAt).isEmpty());

        mapper.originalToken.setConsumedAt(null);
        assertTrue(repository.findUsableByTokenDigest(digest, lastUsedAt).isPresent());
        assertTrue(repository.findUsableByTokenDigest(
                digest, lastUsedAt.plus(Duration.ofHours(9))).isEmpty());
        assertTrue(repository.findUsableSelectionByTokenDigest(digest, lastUsedAt).isEmpty());

        mapper.family.setFamilyPurpose("USER_TENANT_SELECTION");
        assertTrue(repository.findUsableSelectionByTokenDigest(digest, lastUsedAt).isPresent());
        assertFalse(repository.findByTokenDigest(digest).isEmpty());
        mapper.originalToken = null;
        assertTrue(repository.findByTokenDigest(digest).isEmpty());
    }

    @Test
    void consumptionAndSimpleRotationReturnTheirTerminalStates() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        Sha256Digest presented = digest((byte) 1);
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.NOT_FOUND,
                repository.consume(presented, lastUsedAt).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        mapper.originalToken.setConsumedAt(at(lastUsedAt));
        assertEquals(RefreshTokenConsumption.Status.REPLAYED,
                repository.consume(presented, lastUsedAt.plusSeconds(1)).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        mapper.family.setRevokedAt(at(lastUsedAt));
        assertEquals(RefreshTokenConsumption.Status.REVOKED,
                repository.consume(presented, lastUsedAt.plusSeconds(1)).status());

        mapper.family.setRevokedAt(null);
        assertEquals(RefreshTokenConsumption.Status.EXPIRED,
                repository.consume(presented, lastUsedAt.plus(Duration.ofHours(9))).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        mapper.family.setFamilyPurpose("INITIAL_PASSWORD_CHANGE");
        mapper.family.setInitialCredentialId(UUID.randomUUID());
        assertEquals(RefreshTokenConsumption.Status.PURPOSE_MISMATCH,
                repository.rotate(presented, digest((byte) 2), null, null, lastUsedAt.plusSeconds(1)).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                repository.rotate(presented, digest((byte) 2), null, null, lastUsedAt.plusSeconds(1)).status());
    }

    @Test
    void selectionRotationHandlesMissingTerminalSuccessfulAndConflictingUpdates() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        Sha256Digest presented = digest((byte) 1);
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.NOT_FOUND,
                repository.rotateSelection(presented, digest((byte) 2), lastUsedAt).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        mapper.family.setFamilyPurpose("USER_TENANT_SELECTION");
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                repository.rotateSelection(presented, digest((byte) 2), lastUsedAt.plusSeconds(1)).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        mapper.family.setFamilyPurpose("USER_TENANT_SELECTION");
        mapper.originalToken.setConsumedAt(at(lastUsedAt));
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.REPLAYED,
                repository.rotateSelection(presented, digest((byte) 2), lastUsedAt.plusSeconds(1)).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        mapper.family.setFamilyPurpose("USER_TENANT_SELECTION");
        mapper.markTokenConsumedResult = 0;
        MyBatisRefreshTokenFamilyRepository conflictingRepository =
                new MyBatisRefreshTokenFamilyRepository(mapper);
        assertThrows(IllegalStateException.class,
                () -> conflictingRepository.rotateSelection(
                        presented, digest((byte) 2), lastUsedAt.plusSeconds(1)));
    }

    @Test
    void authorizationLossRevocationHandlesMissingExpiredSuccessfulAndConflictingUpdates() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        Sha256Digest presented = digest((byte) 1);
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.NOT_FOUND,
                repository.revokeForAuthorizationLoss(presented, lastUsedAt).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.EXPIRED,
                repository.revokeForAuthorizationLoss(
                        presented, lastUsedAt.plus(Duration.ofHours(9))).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        assertEquals(RefreshTokenConsumption.Status.CONSUMED,
                repository.revokeForAuthorizationLoss(presented, lastUsedAt.plusSeconds(1)).status());

        mapper = mapperWithActiveToken(lastUsedAt);
        mapper.markTokenConsumedResult = 0;
        MyBatisRefreshTokenFamilyRepository conflictingRepository =
                new MyBatisRefreshTokenFamilyRepository(mapper);
        assertThrows(IllegalStateException.class,
                () -> conflictingRepository.revokeForAuthorizationLoss(
                        presented, lastUsedAt.plusSeconds(1)));
    }

    @Test
    void rotationReturnsNotFoundWhenThePresentedTokenDoesNotExist() {
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        RefreshRotation result = repository.rotateForRefresh(
                digest((byte) 1), digest((byte) 2), digest((byte) 3), 0, null, null,
                UUID.randomUUID(), Duration.ofSeconds(5), Instant.parse("2026-08-20T16:00:00Z"));

        assertEquals(RefreshRotation.Status.NOT_FOUND, result.status());
    }

    @Test
    void rotationRejectsRevokedExpiredAndUnsupportedFamilies() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        RecordingRefreshTokenMapper mapper = mapperWithActiveToken(lastUsedAt);
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        mapper.family.setRevokedAt(at(lastUsedAt.plusSeconds(1)));
        assertEquals(RefreshRotation.Status.REVOKED,
                rotate(repository, lastUsedAt.plusSeconds(2)).status());

        mapper.family = family(mapper.family.getId(), lastUsedAt);
        assertEquals(RefreshRotation.Status.EXPIRED,
                rotate(repository, lastUsedAt.plus(Duration.ofHours(9))).status());

        mapper.family = family(mapper.family.getId(), lastUsedAt);
        mapper.family.setFamilyPurpose("INITIAL_PASSWORD_CHANGE");
        mapper.family.setInitialCredentialId(UUID.randomUUID());
        assertEquals(RefreshRotation.Status.PURPOSE_MISMATCH,
                rotate(repository, lastUsedAt.plusSeconds(2)).status());
    }

    @Test
    void firstRotationConsumesThePresentedTokenAndIssuesItsSuccessor() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        Instant rotatedAt = lastUsedAt.plusSeconds(1);
        RecordingRefreshTokenMapper mapper = mapperWithActiveToken(lastUsedAt);
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        RefreshRotation result = rotate(repository, rotatedAt);

        assertEquals(RefreshRotation.Status.ROTATED, result.status());
        assertEquals(rotatedAt, mapper.consumedAt.toInstant());
        assertEquals(rotatedAt, mapper.updatedFamily.getLastUsedAt().toInstant());
        assertEquals(rotatedAt, mapper.insertedToken.getIssuedAt().toInstant());
    }

    @Test
    void rotationLeavesTokensAndFamilyUntouchedWhenContextVersionChanged() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        RecordingRefreshTokenMapper mapper = mapperWithActiveToken(lastUsedAt);
        mapper.family.setContextVersion(1);
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        RefreshRotation result = repository.rotateForRefresh(
                digest((byte) 1), digest((byte) 2), digest((byte) 3), 0, null, null,
                UUID.randomUUID(), Duration.ofSeconds(5), lastUsedAt.plusSeconds(1));

        assertEquals(RefreshRotation.Status.CONTEXT_CHANGED, result.status());
        assertNull(mapper.consumedAt);
        assertNull(mapper.updatedFamily);
        assertNull(mapper.insertedToken);
    }

    @Test
    void tenantContextChangeAdvancesVersionAndRejectsAStaleExpectedVersion() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        RecordingRefreshTokenMapper mapper = mapperWithActiveToken(lastUsedAt);
        mapper.family.setFamilyPurpose("USER_TENANT");
        mapper.family.setMembershipId(UUID.randomUUID());
        mapper.family.setTenantId(UUID.randomUUID());
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        RefreshTokenFamilyContextChange changed = repository.switchTenantContext(
                mapper.family.getId(), 0, UUID.randomUUID(), UUID.randomUUID());
        mapper.family = mapper.updatedFamily;
        RefreshTokenFamilyContextChange conflicted = repository.switchTenantContext(
                mapper.family.getId(), 0, UUID.randomUUID(), UUID.randomUUID());

        assertEquals(RefreshTokenFamilyContextChange.Status.CHANGED, changed.status());
        assertEquals(1, changed.family().contextVersion());
        assertEquals(lastUsedAt, changed.family().lastUsedAt());
        assertEquals(RefreshTokenFamilyContextChange.Status.VERSION_CONFLICT, conflicted.status());
        assertEquals(1, conflicted.family().contextVersion());
    }

    @Test
    void replayWithAnotherKeyDoesNotMoveTheFamilyClockBackwards() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        RecordingRefreshTokenMapper mapper = mapperWithConsumedToken(lastUsedAt, digest((byte) 7));
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        RefreshRotation result = rotate(repository, lastUsedAt.minusNanos(1));

        assertEquals(RefreshRotation.Status.REPLAYED, result.status());
        assertEquals(lastUsedAt, mapper.updatedFamily.getRevokedAt().toInstant());
    }

    @Test
    void replayAfterTheSuccessorWasConsumedUsesTheCurrentRequestTime() {
        Instant lastUsedAt = Instant.parse("2026-08-20T16:00:00Z");
        Instant replayedAt = lastUsedAt.plusSeconds(1);
        Sha256Digest idempotencyKey = digest((byte) 3);
        RecordingRefreshTokenMapper mapper = mapperWithConsumedToken(lastUsedAt, idempotencyKey);
        mapper.successor.setConsumedAt(at(lastUsedAt));
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);

        RefreshRotation result = rotate(repository, replayedAt);

        assertEquals(RefreshRotation.Status.REPLAYED, result.status());
        assertEquals(replayedAt, mapper.updatedFamily.getRevokedAt().toInstant());
    }

    @Test
    void recoveryDoesNotPersistTimestampsBeforeTheSuccessorWasIssued() {
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        MyBatisRefreshTokenFamilyRepository repository = new MyBatisRefreshTokenFamilyRepository(mapper);
        UUID familyId = UUID.randomUUID();
        UUID originalTokenId = UUID.randomUUID();
        UUID successorTokenId = UUID.randomUUID();
        UUID successorAccessJti = UUID.randomUUID();
        Instant successorIssuedAt = Instant.parse("2026-08-20T15:57:32.020741Z");
        Instant staleRequestTime = successorIssuedAt.minusNanos(608_000);
        Sha256Digest presentedDigest = digest((byte) 1);
        Sha256Digest nextDigest = digest((byte) 2);
        Sha256Digest idempotencyKeyDigest = digest((byte) 3);

        RefreshTokenRow originalToken = token(originalTokenId, familyId, successorIssuedAt.minusSeconds(1));
        originalToken.setConsumedAt(at(successorIssuedAt.minusSeconds(1)));
        originalToken.setRotationKeyDigest(idempotencyKeyDigest.value());
        originalToken.setRecoveryExpiresAt(at(successorIssuedAt.plusSeconds(5)));
        originalToken.setSuccessorTokenId(successorTokenId);
        originalToken.setSuccessorAccessJti(successorAccessJti);
        RefreshTokenRow successor = token(successorTokenId, familyId, successorIssuedAt);

        mapper.originalToken = originalToken;
        mapper.family = family(familyId, successorIssuedAt);
        mapper.successor = successor;

        RefreshRotation result = repository.rotateForRefresh(
                presentedDigest, nextDigest, idempotencyKeyDigest, 0, null, null,
                UUID.randomUUID(), Duration.ofSeconds(5), staleRequestTime);

        assertEquals(RefreshRotation.Status.RECOVERED, result.status());
        assertEquals(successorIssuedAt, mapper.consumedAt.toInstant());
        assertEquals(successorIssuedAt, mapper.updatedFamily.getLastUsedAt().toInstant());
        assertEquals(successorIssuedAt, mapper.insertedToken.getIssuedAt().toInstant());
    }

    private static RefreshTokenFamilyRow family(UUID familyId, Instant lastUsedAt) {
        RefreshTokenFamilyRow row = new RefreshTokenFamilyRow();
        row.setId(familyId);
        row.setIdentityId(UUID.randomUUID());
        row.setFamilyPurpose("USER_PLATFORM");
        row.setLastUsedAt(at(lastUsedAt));
        row.setAbsoluteExpiresAt(at(lastUsedAt.plus(Duration.ofHours(8))));
        return row;
    }

    private static RecordingRefreshTokenMapper mapperWithActiveToken(Instant lastUsedAt) {
        RecordingRefreshTokenMapper mapper = new RecordingRefreshTokenMapper();
        UUID familyId = UUID.randomUUID();
        mapper.family = family(familyId, lastUsedAt);
        mapper.originalToken = token(UUID.randomUUID(), familyId, lastUsedAt);
        return mapper;
    }

    private static RecordingRefreshTokenMapper mapperWithConsumedToken(
            Instant lastUsedAt, Sha256Digest idempotencyKey) {
        RecordingRefreshTokenMapper mapper = mapperWithActiveToken(lastUsedAt);
        mapper.originalToken.setConsumedAt(at(lastUsedAt));
        mapper.originalToken.setRotationKeyDigest(idempotencyKey.value());
        mapper.originalToken.setRecoveryExpiresAt(at(lastUsedAt.plusSeconds(5)));
        mapper.originalToken.setSuccessorAccessJti(UUID.randomUUID());
        mapper.successor = token(UUID.randomUUID(), mapper.family.getId(), lastUsedAt);
        mapper.originalToken.setSuccessorTokenId(mapper.successor.getId());
        return mapper;
    }

    private static RefreshRotation rotate(MyBatisRefreshTokenFamilyRepository repository, Instant at) {
        return repository.rotateForRefresh(
                digest((byte) 1), digest((byte) 2), digest((byte) 3), 0, null, null,
                UUID.randomUUID(), Duration.ofSeconds(5), at);
    }

    private static RefreshTokenRow token(UUID tokenId, UUID familyId, Instant issuedAt) {
        RefreshTokenRow row = new RefreshTokenRow();
        row.setId(tokenId);
        row.setFamilyId(familyId);
        row.setIssuedAt(at(issuedAt));
        return row;
    }

    private static Sha256Digest digest(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Sha256Digest.of(bytes);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final class RecordingRefreshTokenMapper implements RefreshTokenMapper {
        private RefreshTokenRow originalToken;
        private RefreshTokenFamilyRow family;
        private RefreshTokenRow successor;
        private OffsetDateTime consumedAt;
        private RefreshTokenFamilyRow updatedFamily;
        private RefreshTokenRow insertedToken;
        private int markTokenConsumedResult = 1;

        @Override
        public RefreshTokenFamilyRow insertFamily(RefreshTokenFamilyRow row) {
            return row;
        }

        @Override
        public RefreshTokenRow insertToken(RefreshTokenRow row) {
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            insertedToken = row;
            return row;
        }

        @Override
        public RefreshTokenFamilyRow findFamilyById(UUID familyId) {
            return family;
        }

        @Override
        public RefreshTokenRow findTokenByDigest(byte[] tokenDigest) {
            return originalToken;
        }

        @Override
        public RefreshTokenRow lockTokenById(UUID tokenId) {
            return successor;
        }

        @Override
        public RefreshTokenRow lockTokenByDigest(byte[] tokenDigest) {
            return originalToken;
        }

        @Override
        public RefreshTokenFamilyRow lockFamilyById(UUID familyId) {
            return family;
        }

        @Override
        public int markTokenConsumed(UUID tokenId, OffsetDateTime consumedAt) {
            this.consumedAt = consumedAt;
            return markTokenConsumedResult;
        }

        @Override
        public int updateFamily(RefreshTokenFamilyRow row) {
            updatedFamily = row;
            return 1;
        }

        @Override
        public int recordRotation(
                UUID tokenId,
                byte[] rotationKeyDigest,
                OffsetDateTime recoveryExpiresAt,
                UUID successorTokenId,
                UUID successorAccessJti) {
            return 1;
        }

        @Override
        public int markRecovered(UUID tokenId, OffsetDateTime recoveredAt) {
            return 1;
        }

        @Override
        public int revokeInitialPasswordChangeFamilies(UUID identityId, OffsetDateTime revokedAt) {
            return 0;
        }
    }
}
