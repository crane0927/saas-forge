package io.saasforge.entitlement.application.bootstrap;

import io.saasforge.entitlement.domain.plan.Plan;
import io.saasforge.entitlement.domain.plan.PlanQuotaLimit;
import io.saasforge.entitlement.domain.plan.PlanStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanResult(
        UUID id,
        String code,
        String displayName,
        PlanStatus status,
        List<PlanQuotaLimit> quotaLimits,
        Instant createdAt,
        Instant updatedAt) {
    public PlanResult {
        quotaLimits = List.copyOf(quotaLimits);
    }

    static PlanResult from(Plan plan) {
        return new PlanResult(
                plan.id(), plan.code(), plan.displayName(), plan.status(), plan.quotaLimits(),
                plan.createdAt(), plan.updatedAt());
    }
}
