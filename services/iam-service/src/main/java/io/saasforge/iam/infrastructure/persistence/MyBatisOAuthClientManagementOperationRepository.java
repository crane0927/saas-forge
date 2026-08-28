package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.client.OAuthClientManagementOperation;
import io.saasforge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.OAuthClientManagementOperationMapper;
import io.saasforge.iam.infrastructure.persistence.record.OAuthClientManagementOperationRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOAuthClientManagementOperationRepository
        implements OAuthClientManagementOperationRepository {
    private final OAuthClientManagementOperationMapper mapper;

    public MyBatisOAuthClientManagementOperationRepository(OAuthClientManagementOperationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean tryLock(UUID actorIdentityId, UUID idempotencyKey) {
        return mapper.tryLock(actorIdentityId + ":" + idempotencyKey);
    }

    @Override
    public Optional<OAuthClientManagementOperation> find(UUID actorIdentityId, UUID idempotencyKey) {
        return map(mapper.find(actorIdentityId, idempotencyKey));
    }

    @Override
    public boolean tryLockRecovery(UUID originalOperationId) {
        return mapper.tryLock("RECOVER:" + originalOperationId);
    }

    @Override
    public Optional<OAuthClientManagementOperation> findSuccessfulRecovery(UUID originalOperationId) {
        return map(mapper.findSuccessfulRecovery(originalOperationId));
    }

    @Override
    public void append(OAuthClientManagementOperation operation) {
        OAuthClientManagementOperationRow row = new OAuthClientManagementOperationRow();
        row.setId(operation.id());
        row.setActorIdentityId(operation.actorIdentityId());
        row.setIdempotencyKey(operation.idempotencyKey());
        row.setOperationType(operation.operationType());
        row.setClientId(operation.clientId());
        row.setRequestFingerprint(operation.requestFingerprint().value());
        row.setOriginalOperationId(operation.originalOperationId());
        row.setSecretRecordId(operation.secretRecordId());
        row.setOutcome(operation.outcome());
        row.setHttpStatus(operation.httpStatus());
        row.setCompletedAt(IamTime.asOffsetDateTime(operation.completedAt()));
        if (mapper.insert(row) != 1) {
            throw new IllegalStateException("OAuth Client 管理操作终态写入失败");
        }
    }

    private static Optional<OAuthClientManagementOperation> map(OAuthClientManagementOperationRow row) {
        return Optional.ofNullable(row).map(value -> new OAuthClientManagementOperation(
                value.getId(), value.getActorIdentityId(), value.getIdempotencyKey(), value.getOperationType(),
                value.getClientId(), Sha256Digest.of(value.getRequestFingerprint()),
                value.getOriginalOperationId(), value.getSecretRecordId(), value.getOutcome(),
                value.getHttpStatus(), IamTime.asInstant(value.getCompletedAt())));
    }
}
