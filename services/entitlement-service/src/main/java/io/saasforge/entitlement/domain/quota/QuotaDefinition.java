package io.saasforge.entitlement.domain.quota;

import java.time.Instant;
import java.util.UUID;

public record QuotaDefinition(
        UUID id,
        String code,
        QuotaDefinitionStatus status,
        Instant createdAt,
        Instant updatedAt) {
    public static final String MAX_USERS = "max_users";

    public static QuotaDefinition draft(UUID id, String code, Instant now) {
        if (id == null || id.version() != 7) {
            throw new IllegalArgumentException("Quota Definition ID 必须是 UUIDv7");
        }
        if (!MAX_USERS.equals(code)) {
            throw new QuotaDefinitionInvalidException("当前仅支持 max_users Quota Definition");
        }
        return new QuotaDefinition(id, code, QuotaDefinitionStatus.DRAFT, now, now);
    }

    public QuotaDefinition activate(Instant now) {
        if (status != QuotaDefinitionStatus.DRAFT) {
            throw new QuotaDefinitionTransitionException();
        }
        return new QuotaDefinition(id, code, QuotaDefinitionStatus.ACTIVE, createdAt, now);
    }
}
