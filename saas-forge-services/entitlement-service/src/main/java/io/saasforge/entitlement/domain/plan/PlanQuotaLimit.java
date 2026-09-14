package io.saasforge.entitlement.domain.plan;

import java.util.UUID;

public record PlanQuotaLimit(UUID quotaDefinitionId, int limit) {
    public PlanQuotaLimit {
        if (quotaDefinitionId == null || quotaDefinitionId.version() != 7 || limit < 0) {
            throw new PlanInvalidException("Plan 的 max_users 限额不合法");
        }
    }
}
