package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetFact;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetRepository;
import io.saasforge.iam.infrastructure.persistence.mapper.PlatformAdminCredentialResetMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPlatformAdminCredentialResetRepository implements PlatformAdminCredentialResetRepository {
    private final PlatformAdminCredentialResetMapper mapper;

    public MyBatisPlatformAdminCredentialResetRepository(PlatformAdminCredentialResetMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockReset() {
        if (mapper.lockReset() != 1) {
            throw new IllegalStateException("Platform Admin 初始凭证重置锁获取失败");
        }
    }

    @Override
    public Optional<PlatformAdminCredentialResetFact> findByRequestId(UUID resetRequestId) {
        return Optional.ofNullable(mapper.findByRequestId(resetRequestId)).map(row ->
                new PlatformAdminCredentialResetFact(
                        row.getResetRequestId(), row.getIdentityId(), row.getCredentialId(),
                        row.getEventId(), IamTime.asInstant(row.getResetAt())));
    }

    @Override
    public void create(PlatformAdminCredentialResetFact fact) {
        if (mapper.insert(fact.resetRequestId(), fact.identityId(), fact.credentialId(), fact.eventId(),
                IamTime.asOffsetDateTime(fact.resetAt())) != 1) {
            throw new IllegalStateException("Platform Admin 初始凭证重置事实保存失败");
        }
    }
}
