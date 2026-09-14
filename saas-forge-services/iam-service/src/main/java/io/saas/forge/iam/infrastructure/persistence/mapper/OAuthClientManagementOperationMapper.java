package io.saas.forge.iam.infrastructure.persistence.mapper;

import io.saas.forge.iam.infrastructure.persistence.record.OAuthClientManagementOperationRow;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface OAuthClientManagementOperationMapper {
    OAuthClientManagementOperationRow findById(@Param("actorIdentityId") UUID actorIdentityId,
            @Param("operationId") UUID operationId);
    boolean tryLock(@Param("lockKey") String lockKey);

    OAuthClientManagementOperationRow find(
            @Param("actorIdentityId") UUID actorIdentityId,
            @Param("idempotencyKey") UUID idempotencyKey);

    OAuthClientManagementOperationRow findSuccessfulRecovery(
            @Param("originalOperationId") UUID originalOperationId);

    int insert(@Param("row") OAuthClientManagementOperationRow row);
}
