package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.session.AccessTokenIssuance;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.infrastructure.persistence.mapper.AccessTokenIssuanceMapper;
import io.saasforge.iam.infrastructure.persistence.record.AccessTokenIssuanceRow;
import org.springframework.stereotype.Repository;

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
}
