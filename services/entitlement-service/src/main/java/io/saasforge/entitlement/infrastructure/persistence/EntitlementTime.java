package io.saasforge.entitlement.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class EntitlementTime {
    private EntitlementTime() {
    }

    static OffsetDateTime asOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static Instant asInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
