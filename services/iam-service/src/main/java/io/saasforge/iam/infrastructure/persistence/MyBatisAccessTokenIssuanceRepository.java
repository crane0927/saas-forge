package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.domain.session.DurableRevocation;
import io.saasforge.iam.infrastructure.persistence.mapper.AccessTokenIssuanceMapper;
import io.saasforge.iam.infrastructure.persistence.record.AccessTokenIssuanceRow;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MyBatisAccessTokenIssuanceRepository implements AccessTokenIssuanceRepository {
    private final AccessTokenIssuanceMapper mapper;

    public MyBatisAccessTokenIssuanceRepository(AccessTokenIssuanceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void create(AccessTokenIssuance issuance) {
        AccessTokenIssuanceRow row = new AccessTokenIssuanceRow();
        row.setJti(issuance.jti());
        row.setFamilyId(issuance.familyId());
        row.setIdentityId(issuance.identityId());
        row.setMembershipId(issuance.membershipId());
        row.setTenantId(issuance.tenantId());
        row.setKid(issuance.kid());
        row.setIssuedAt(IamTime.asOffsetDateTime(issuance.issuedAt()));
        row.setExpiresAt(IamTime.asOffsetDateTime(issuance.expiresAt()));
        if (mapper.insert(row) != 1) {
            throw new IllegalStateException("Access Token Issuance 保存失败");
        }
    }

    @Override
    public Optional<AccessTokenIssuance> findByJti(UUID jti) {
        return Optional.ofNullable(mapper.findByJti(jti)).map(MyBatisAccessTokenIssuanceRepository::toDomain);
    }

    @Override
    public boolean revoke(UUID jti, Instant revokedAt, String reason) {
        return mapper.revoke(jti, IamTime.asOffsetDateTime(revokedAt), reason) == 1;
    }

    @Override
    public List<DurableRevocation> findUnexpiredRevocations(Instant at) {
        return mapper.findUnexpiredRevocations(IamTime.asOffsetDateTime(at)).stream()
                .map(row -> new DurableRevocation(
                        row.getJti(), row.getKid(), row.getExpiresAt().toInstant(),
                        row.isJtiRevoked(), row.isKidRevoked()))
                .toList();
    }

    private static AccessTokenIssuance toDomain(AccessTokenIssuanceRow row) {
        return new AccessTokenIssuance(
                row.getJti(), row.getFamilyId(), row.getIdentityId(), row.getMembershipId(), row.getTenantId(),
                row.getKid(), row.getIssuedAt().toInstant(), row.getExpiresAt().toInstant());
    }
}
