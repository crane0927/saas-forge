package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.PasswordSetupDeliveryRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface PasswordSetupDeliveryMapper {
    int lockRequest(@Param("lockKey") String lockKey);
    PasswordSetupDeliveryRow find(@Param("callerClientId") UUID callerClientId, @Param("requestId") UUID requestId);
    int insertPasswordReady(@Param("row") PasswordSetupDeliveryRow row);
    int markPasswordReady(
            @Param("callerClientId") UUID callerClientId,
            @Param("requestId") UUID requestId,
            @Param("identityId") UUID identityId,
            @Param("completedAt") OffsetDateTime completedAt);
    int upsertPending(@Param("row") PasswordSetupDeliveryRow row);
    int markDelivered(
            @Param("callerClientId") UUID callerClientId,
            @Param("requestId") UUID requestId,
            @Param("challengeId") UUID challengeId,
            @Param("deliveredAt") OffsetDateTime deliveredAt);
}
