package io.saasforge.entitlement.domain.quota;

import java.time.Instant;
import java.util.UUID;

/** operationId 永久绑定完整请求指纹；完成结果用于稳定重放成功或业务失败。 */
public record QuotaOperation(
        UUID operationId,
        UUID callerClientId,
        UUID tenantId,
        String quotaCode,
        int amount,
        QuotaOperationAction action,
        QuotaOperationPurpose purpose,
        String requestFingerprint,
        QuotaOperationOutcome outcome,
        Integer usage,
        Integer limit,
        Instant createdAt,
        Instant completedAt) {

    public boolean completed() {
        return outcome != null;
    }
}
