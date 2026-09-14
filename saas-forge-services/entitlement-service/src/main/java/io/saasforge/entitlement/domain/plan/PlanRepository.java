package io.saasforge.entitlement.domain.plan;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository {
    boolean create(Plan plan);

    Optional<Plan> findById(UUID id);

    boolean activate(UUID id, Instant updatedAt);
}
