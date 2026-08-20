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
                RefreshTokenFamilyPurpose.valueOf(row.getFamilyPurpose()), row.getMembershipId(), row.getTenantId(),
                IamTime.asInstant(row.getLastUsedAt()), IamTime.asInstant(row.getAbsoluteExpiresAt()),
                IamTime.asInstant(row.getRevokedAt()));
    }
}
