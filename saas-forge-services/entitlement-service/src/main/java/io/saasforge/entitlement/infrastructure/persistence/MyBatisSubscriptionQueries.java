package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.subscription.SubscriptionQueries;
import io.saasforge.entitlement.application.subscription.InitialSubscriptionResult;
import io.saasforge.entitlement.domain.subscription.SubscriptionStatus;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Repository
public class MyBatisSubscriptionQueries implements SubscriptionQueries {
    private final EntitlementBootstrapMapper mapper;
    private final Clock clock;

    public MyBatisSubscriptionQueries(EntitlementBootstrapMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Result get(UUID tenantId) {
        if (tenantId == null || tenantId.version() != 7) throw new IllegalArgumentException("Invalid Tenant ID");
        // 事务内设置 RLS 作用域，订阅、额度和用量来自同一数据库快照。
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Entitlement Tenant scope unavailable");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        var row = mapper.findSubscription(tenantId);
        if (row == null) return new Result(now, null, false, null, null);
        var definition = mapper.findQuotaDefinitionIdByCode("max_users");
        var limit = mapper.findGrantedQuotaLimit(tenantId, definition);
        if (limit == null) throw new IllegalStateException("Subscription quota grant unavailable");
        var used = mapper.findSubscriptionUsage(tenantId, definition);
        var endsAt = EntitlementTime.asInstant(row.endsAt());
        return new Result(now, new InitialSubscriptionResult(row.id(), row.tenantId(), row.planId(),
                SubscriptionStatus.valueOf(row.status()), endsAt, EntitlementTime.asInstant(row.createdAt())),
                endsAt == null || now.isBefore(endsAt), limit, used);
    }
}
