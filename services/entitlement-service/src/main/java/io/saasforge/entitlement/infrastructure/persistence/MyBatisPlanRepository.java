package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.domain.plan.Plan;
import io.saasforge.entitlement.domain.plan.PlanQuotaLimit;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.domain.plan.PlanStatus;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.PlanQuotaLimitRow;
import io.saasforge.entitlement.infrastructure.persistence.record.PlanRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPlanRepository implements PlanRepository {
    private final EntitlementBootstrapMapper mapper;

    public MyBatisPlanRepository(EntitlementBootstrapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean create(Plan plan) {
        int inserted = mapper.insertPlan(new PlanRow(
                plan.id(), plan.code(), plan.displayName(), plan.status().name(),
                EntitlementTime.asOffsetDateTime(plan.createdAt()),
                EntitlementTime.asOffsetDateTime(plan.updatedAt())));
        if (inserted == 0) {
            return false;
        }
        PlanQuotaLimit limit = plan.quotaLimits().get(0);
        if (mapper.insertPlanQuotaLimit(new PlanQuotaLimitRow(
                plan.id(), limit.quotaDefinitionId(), limit.limit())) != 1) {
            throw new IllegalStateException("Plan Quota Limit 保存失败");
        }
        return true;
    }

    @Override
    public Optional<Plan> findById(UUID id) {
        PlanRow row = mapper.findPlan(id);
        if (row == null) {
            return Optional.empty();
        }
        var limits = mapper.findPlanQuotaLimits(id).stream()
                .map(limit -> new PlanQuotaLimit(limit.quotaDefinitionId(), limit.quotaLimit()))
                .toList();
        return Optional.of(new Plan(
                row.id(), row.code(), row.displayName(), PlanStatus.valueOf(row.status()), limits,
                EntitlementTime.asInstant(row.createdAt()), EntitlementTime.asInstant(row.updatedAt())));
    }

    @Override
    public boolean activate(UUID id, Instant updatedAt) {
        return mapper.activatePlan(id, EntitlementTime.asOffsetDateTime(updatedAt)) == 1;
    }
}
