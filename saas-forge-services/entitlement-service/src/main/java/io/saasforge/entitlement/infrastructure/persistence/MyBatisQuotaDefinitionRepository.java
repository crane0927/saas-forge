package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.domain.quota.QuotaDefinition;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionRepository;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionStatus;
import io.saasforge.entitlement.infrastructure.persistence.mapper.EntitlementBootstrapMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.QuotaDefinitionRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisQuotaDefinitionRepository implements QuotaDefinitionRepository {
    private final EntitlementBootstrapMapper mapper;

    public MyBatisQuotaDefinitionRepository(EntitlementBootstrapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean create(QuotaDefinition definition) {
        return mapper.insertQuotaDefinition(new QuotaDefinitionRow(
                definition.id(), definition.code(), definition.status().name(),
                EntitlementTime.asOffsetDateTime(definition.createdAt()),
                EntitlementTime.asOffsetDateTime(definition.updatedAt()))) == 1;
    }

    @Override
    public Optional<QuotaDefinition> findById(UUID id) {
        return Optional.ofNullable(mapper.findQuotaDefinition(id)).map(row -> new QuotaDefinition(
                row.id(), row.code(), QuotaDefinitionStatus.valueOf(row.status()),
                EntitlementTime.asInstant(row.createdAt()), EntitlementTime.asInstant(row.updatedAt())));
    }

    @Override
    public boolean activate(UUID id, Instant updatedAt) {
        return mapper.activateQuotaDefinition(id, EntitlementTime.asOffsetDateTime(updatedAt)) == 1;
    }
}
