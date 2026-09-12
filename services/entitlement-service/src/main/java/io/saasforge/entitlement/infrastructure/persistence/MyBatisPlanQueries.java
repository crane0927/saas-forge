package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.bootstrap.PlanQueries;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.domain.plan.PlanNotFoundException;
import io.saasforge.entitlement.domain.plan.PlanStatus;
import io.saasforge.entitlement.domain.plan.PlanRepository;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisPlanQueries implements PlanQueries {
    private final EntitlementBootstrapMapper mapper;
    private final PlanRepository plans;
    private final QuotaDefinitionCursor cursors;
    public MyBatisPlanQueries(EntitlementBootstrapMapper mapper, PlanRepository plans, Clock clock) {
        this.mapper = mapper;
        this.plans = plans;
        cursors = new QuotaDefinitionCursor(clock);
    }
    @Override
    public Page list(String code, PlanStatus status, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (code != null && code.length() > 100)) {
            throw new IllegalArgumentException("Invalid filter or page size");
        }
        String filter = code == null ? "" : code;
        String scope = "plans:" + filter + ":" + status;
        var rows = mapper.listPlans(new EntitlementBootstrapMapper.QuotaQuery(
                filter, status == null ? null : status.name(), cursors.decode(scope, cursor), limit + 1));
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(this::get).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }
    @Override
    public PlanResult get(UUID id) {
        return PlanResult.from(plans.findById(id).orElseThrow(PlanNotFoundException::new));
    }
}
