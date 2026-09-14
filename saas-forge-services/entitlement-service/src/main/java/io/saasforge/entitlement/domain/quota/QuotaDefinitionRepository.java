package io.saasforge.entitlement.domain.quota;

import java.util.Optional;
import java.util.UUID;

public interface QuotaDefinitionRepository {
    boolean create(QuotaDefinition definition);

    Optional<QuotaDefinition> findById(UUID id);

    boolean activate(UUID id, java.time.Instant updatedAt);
}
