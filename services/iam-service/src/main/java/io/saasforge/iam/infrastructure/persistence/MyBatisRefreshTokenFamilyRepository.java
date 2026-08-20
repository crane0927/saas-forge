package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.session.RefreshTokenConsumption;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.RefreshTokenMapper;
import io.saasforge.iam.infrastructure.persistence.record.RefreshTokenFamilyRow;
import io.saasforge.iam.infrastructure.persistence.record.RefreshTokenRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisRefreshTokenFamilyRepository implements RefreshTokenFamilyRepository {

    private final RefreshTokenMapper mapper;

    public MyBatisRefreshTokenFamilyRepository(RefreshTokenMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public RefreshTokenFamily create(RefreshTokenFamily family, Sha256Digest tokenDigest, Instant issuedAt) {
        RefreshTokenFamily persisted = toDomain(mapper.insertFamily(toRow(family)));
        mapper.insertToken(tokenRow(persisted.id(), tokenDigest, issuedAt));
        return persisted;
    }

    @Override
    public Optional<RefreshTokenFamily> findById(UUID familyId) {
        return Optional.ofNullable(mapper.findFamilyById(familyId)).map(MyBatisRefreshTokenFamilyRepository::toDomain);
    }

    @Override
    public Optional<RefreshTokenFamily> findUsableSelectionByTokenDigest(Sha256Digest tokenDigest, Instant at) {
        return findUsableByTokenDigest(tokenDigest, at)
                .filter(family -> family.purpose() == RefreshTokenFamilyPurpose.USER_TENANT_SELECTION);
    }

    @Override
    public Optional<RefreshTokenFamily> findUsableByTokenDigest(Sha256Digest tokenDigest, Instant at) {
        RefreshTokenRow token = mapper.findTokenByDigest(tokenDigest.value());
        if (token == null || token.getConsumedAt() != null) {
            return Optional.empty();
        }
        return findById(token.getFamilyId())
                .filter(family -> family.isUsableAt(at));
    }

    @Override
    @Transactional
    public RefreshTokenConsumption consume(Sha256Digest tokenDigest, Instant at) {
        return consumeLocked(tokenDigest, null, null, null, at);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption rotate(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at) {
        return consumeLocked(presentedDigest, nextDigest, membershipId, tenantId, at);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption selectTenantContext(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        RefreshTokenConsumption terminal = terminalSelectionState(token, family, at);
        if (terminal != null) {
            return terminal;
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily selected = family.selectTenant(membershipId, tenantId, at);
        mapper.updateFamily(toRow(selected));
        mapper.insertToken(tokenRow(selected.id(), nextDigest, at));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, selected);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption rotateSelection(
            Sha256Digest presentedDigest, Sha256Digest nextDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        RefreshTokenConsumption terminal = terminalSelectionState(token, family, at);
        if (terminal != null) {
            return terminal;
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily used = family.recordUse(null, null, at);
        mapper.updateFamily(toRow(used));
        mapper.insertToken(tokenRow(used.id(), nextDigest, at));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, used);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption revokeForAuthorizationLoss(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (token.getConsumedAt() != null || family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily revoked = family.revoke(at);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption rejectSelection(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        RefreshTokenConsumption terminal = terminalSelectionState(token, family, at);
        if (terminal != null) {
            return terminal;
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily revoked = family.revoke(at);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    @Override
    @Transactional
    public RefreshTokenConsumption consumeInitialPasswordChange(Sha256Digest presentedDigest, Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (token.getConsumedAt() != null || family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (family.purpose() != RefreshTokenFamilyPurpose.INITIAL_PASSWORD_CHANGE) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.PURPOSE_MISMATCH, family);
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily revoked = family.revoke(at);
        mapper.updateFamily(toRow(revoked));
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, revoked);
    }

    private RefreshTokenConsumption terminalSelectionState(
            RefreshTokenRow token, RefreshTokenFamily family, Instant at) {
        if (token.getConsumedAt() != null) {
            Instant revokedAt = at.isBefore(family.lastUsedAt()) ? family.lastUsedAt() : at;
            RefreshTokenFamily revoked = family.revoke(revokedAt);
            mapper.updateFamily(toRow(revoked));
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REPLAYED, revoked);
        }
        if (family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT_SELECTION) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.PURPOSE_MISMATCH, family);
        }
        return null;
    }

    private RefreshTokenConsumption consumeLocked(
            Sha256Digest presentedDigest,
            Sha256Digest nextDigest,
            UUID membershipId,
            UUID tenantId,
            Instant at) {
        RefreshTokenRow token = mapper.lockTokenByDigest(presentedDigest.value());
        if (token == null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.NOT_FOUND, null);
        }
        RefreshTokenFamily family = toDomain(mapper.lockFamilyById(token.getFamilyId()));
        if (token.getConsumedAt() != null) {
            RefreshTokenFamily revoked = family.revoke(at);
            mapper.updateFamily(toRow(revoked));
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REPLAYED, revoked);
        }
        if (family.revokedAt() != null) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.REVOKED, family);
        }
        if (!family.isUsableAt(at)) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.EXPIRED, family);
        }
        if (nextDigest != null
                && family.purpose() != RefreshTokenFamilyPurpose.USER_PLATFORM
                && family.purpose() != RefreshTokenFamilyPurpose.USER_TENANT) {
            return new RefreshTokenConsumption(RefreshTokenConsumption.Status.PURPOSE_MISMATCH, family);
        }
        if (mapper.markTokenConsumed(token.getId(), IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("Refresh Token 消费并发冲突");
        }
        RefreshTokenFamily used = family.recordUse(
                nextDigest == null ? family.membershipId() : membershipId,
                nextDigest == null ? family.tenantId() : tenantId,
                at);
        mapper.updateFamily(toRow(used));
        if (nextDigest != null) {
            mapper.insertToken(tokenRow(used.id(), nextDigest, at));
        }
        return new RefreshTokenConsumption(RefreshTokenConsumption.Status.CONSUMED, used);
    }

    private static RefreshTokenFamilyRow toRow(RefreshTokenFamily family) {
        RefreshTokenFamilyRow row = new RefreshTokenFamilyRow();
        row.setId(family.id());
        row.setIdentityId(family.identityId());
        row.setFamilyPurpose(family.purpose().name());
        row.setInitialCredentialId(family.initialCredentialId());
        row.setMembershipId(family.membershipId());
        row.setTenantId(family.tenantId());
        row.setLastUsedAt(IamTime.asOffsetDateTime(family.lastUsedAt()));
        row.setAbsoluteExpiresAt(IamTime.asOffsetDateTime(family.absoluteExpiresAt()));
        row.setRevokedAt(IamTime.asOffsetDateTime(family.revokedAt()));
        return row;
    }

    private static RefreshTokenRow tokenRow(UUID familyId, Sha256Digest digest, Instant issuedAt) {
        RefreshTokenRow row = new RefreshTokenRow();
        row.setFamilyId(familyId);
        row.setTokenDigest(digest.value());
        row.setIssuedAt(IamTime.asOffsetDateTime(issuedAt));
        return row;
    }

    private static RefreshTokenFamily toDomain(RefreshTokenFamilyRow row) {
        return RefreshTokenFamily.restore(row.getId(), row.getIdentityId(),
                RefreshTokenFamilyPurpose.valueOf(row.getFamilyPurpose()), row.getInitialCredentialId(),
                row.getMembershipId(), row.getTenantId(),
                IamTime.asInstant(row.getLastUsedAt()), IamTime.asInstant(row.getAbsoluteExpiresAt()),
                IamTime.asInstant(row.getRevokedAt()));
    }
}
