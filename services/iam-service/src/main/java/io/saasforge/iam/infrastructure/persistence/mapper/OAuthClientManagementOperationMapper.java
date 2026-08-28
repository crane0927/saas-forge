package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.OAuthClientManagementOperationRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface OAuthClientManagementOperationMapper {
    boolean tryLock(@Param("lockKey") String lockKey);

    OAuthClientManagementOperationRow find(
            @Param("actorIdentityId") UUID actorIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey);

    OAuthClientManagementOperationRow findSuccessfulRecovery(
            @Param("originalOperationId") UUID originalOperationId);

    int insert(@Param("row") OAuthClientManagementOperationRow row);
}
