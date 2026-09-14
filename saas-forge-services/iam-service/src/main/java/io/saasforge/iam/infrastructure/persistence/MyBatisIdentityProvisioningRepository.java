package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.identity.IdentityCredentialStatus;
import io.saasforge.iam.domain.identity.IdentityProvisioningFact;
import io.saasforge.iam.domain.identity.IdentityProvisioningRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.IdentityProvisioningMapper;
import io.saasforge.iam.infrastructure.persistence.record.IdentityProvisioningFactRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisIdentityProvisioningRepository implements IdentityProvisioningRepository {
    private final IdentityProvisioningMapper mapper;

    public MyBatisIdentityProvisioningRepository(IdentityProvisioningMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockRequest(UUID callerClientId, UUID requestId) {
        if (mapper.lockRequest(callerClientId + ":" + requestId) != 1) {
            throw new IllegalStateException("Identity 确保请求锁获取失败");
        }
    }

    @Override
    public Optional<IdentityProvisioningFact> find(UUID callerClientId, UUID requestId) {
        IdentityProvisioningFactRow key = new IdentityProvisioningFactRow();
        key.setCallerClientId(callerClientId);
        key.setRequestId(requestId);
        return Optional.ofNullable(mapper.find(key)).map(MyBatisIdentityProvisioningRepository::toDomain);
    }

    @Override
    public void create(IdentityProvisioningFact fact) {
        if (mapper.insert(toRow(fact)) != 1) {
            throw new IllegalStateException("Identity 确保事实保存失败");
        }
    }

    private static IdentityProvisioningFactRow toRow(IdentityProvisioningFact fact) {
        IdentityProvisioningFactRow row = new IdentityProvisioningFactRow();
        row.setCallerClientId(fact.callerClientId());
        row.setRequestId(fact.requestId());
        row.setRequestFingerprint(fact.requestFingerprint().value());
        row.setIdentityId(fact.identityId());
        row.setCredentialStatus(fact.credentialStatus().name());
        row.setEnsuredAt(IamTime.asOffsetDateTime(fact.ensuredAt()));
        return row;
    }

    private static IdentityProvisioningFact toDomain(IdentityProvisioningFactRow row) {
        return new IdentityProvisioningFact(
                row.getCallerClientId(), row.getRequestId(), Sha256Digest.of(row.getRequestFingerprint()),
                row.getIdentityId(), IdentityCredentialStatus.valueOf(row.getCredentialStatus()),
                IamTime.asInstant(row.getEnsuredAt()));
    }
}
