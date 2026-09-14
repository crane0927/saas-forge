package io.saasforge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 不含任何凭据及摘要的操作查询投影。 */
public record OAuthClientOperationProjection(UUID operationId, UUID clientId, String displayName,
        String action, OffsetDateTime completedAt, OffsetDateTime recoveryUntil, boolean canRecover) { }
