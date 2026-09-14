package io.saas.forge.entitlement.application.bootstrap;

import io.saas.forge.entitlement.domain.quota.QuotaDefinition;
import io.saas.forge.entitlement.domain.quota.QuotaDefinitionStatus;
import java.time.Instant;
import java.util.UUID;

public record QuotaDefinitionResult(
        UUID id, String code, QuotaDefinitionStatus status, Instant createdAt, Instant updatedAt) {
    static QuotaDefinitionResult from(QuotaDefinition definition) {
        return new QuotaDefinitionResult(
                definition.id(), definition.code(), definition.status(),
                definition.createdAt(), definition.updatedAt());
    }
}
