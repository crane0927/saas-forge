package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.identity.PasswordSetupDelivery;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryRepository;
import io.saasforge.iam.domain.identity.PasswordSetupDeliveryStatus;
import io.saasforge.iam.infrastructure.persistence.mapper.PasswordSetupDeliveryMapper;
import io.saasforge.iam.infrastructure.persistence.record.PasswordSetupDeliveryRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPasswordSetupDeliveryRepository implements PasswordSetupDeliveryRepository {
    private final PasswordSetupDeliveryMapper mapper;

    public MyBatisPasswordSetupDeliveryRepository(PasswordSetupDeliveryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void lockRequest(UUID callerClientId, UUID requestId) {
        if (mapper.lockRequest(callerClientId + ":" + requestId) != 1) {
            throw new IllegalStateException("Password Setup 投递请求锁获取失败");
        }
    }

    @Override
    public Optional<PasswordSetupDelivery> find(UUID callerClientId, UUID requestId) {
        return Optional.ofNullable(mapper.find(callerClientId, requestId))
                .map(MyBatisPasswordSetupDeliveryRepository::toDomain);
    }

    @Override
    public void savePasswordReady(UUID callerClientId, UUID requestId, UUID identityId, Instant completedAt) {
        PasswordSetupDeliveryRow row = key(callerClientId, requestId, identityId);
        row.setCompletedAt(IamTime.asOffsetDateTime(completedAt));
        if (mapper.insertPasswordReady(row) != 1) {
            throw new IllegalStateException("Password Setup 已有密码事实保存失败");
        }
    }

    @Override
    public boolean markPasswordReady(
            UUID callerClientId, UUID requestId, UUID identityId, Instant completedAt) {
        return mapper.markPasswordReady(
                callerClientId, requestId, identityId, IamTime.asOffsetDateTime(completedAt)) == 1;
    }

    @Override
    public void savePending(
            UUID callerClientId, UUID requestId, UUID identityId, UUID challengeId, Instant challengeExpiresAt) {
        PasswordSetupDeliveryRow row = key(callerClientId, requestId, identityId);
        row.setChallengeId(challengeId);
        row.setChallengeExpiresAt(IamTime.asOffsetDateTime(challengeExpiresAt));
        if (mapper.upsertPending(row) != 1) {
            throw new IllegalStateException("Password Setup 投递请求与已有请求冲突");
        }
    }

    @Override
    public boolean markDelivered(
            UUID callerClientId, UUID requestId, UUID challengeId, Instant deliveredAt) {
        return mapper.markDelivered(callerClientId, requestId, challengeId, IamTime.asOffsetDateTime(deliveredAt)) == 1;
    }

    private static PasswordSetupDeliveryRow key(UUID callerClientId, UUID requestId, UUID identityId) {
        PasswordSetupDeliveryRow row = new PasswordSetupDeliveryRow();
        row.setCallerClientId(callerClientId);
        row.setRequestId(requestId);
        row.setIdentityId(identityId);
        return row;
    }

    private static PasswordSetupDelivery toDomain(PasswordSetupDeliveryRow row) {
        return new PasswordSetupDelivery(
                row.getCallerClientId(), row.getRequestId(), row.getIdentityId(),
                PasswordSetupDeliveryStatus.valueOf(row.getStatus()), row.getChallengeId(),
                IamTime.asInstant(row.getChallengeExpiresAt()), IamTime.asInstant(row.getCompletedAt()));
    }
}
