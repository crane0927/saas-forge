package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.domain.subscription.Subscription;
import io.saasforge.entitlement.domain.subscription.SubscriptionRepository;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.SubscriptionRow;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public final class MyBatisSubscriptionRepository implements SubscriptionRepository {
    private final EntitlementBootstrapMapper mapper;

    public MyBatisSubscriptionRepository(EntitlementBootstrapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void setOperationTarget(UUID tenantId) {
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Entitlement Tenant Operation Target 设置失败");
        }
    }

    @Override
    public boolean create(Subscription subscription) {
        return mapper.insertSubscription(new SubscriptionRow(
                subscription.id(), subscription.tenantId(), subscription.planId(),
                subscription.status().name(), EntitlementTime.asOffsetDateTime(subscription.endsAt()),
                EntitlementTime.asOffsetDateTime(subscription.createdAt()))) == 1;
    }
}
