package io.saasforge.iam.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class IamTime {

    private IamTime() {
    }

    static OffsetDateTime asOffsetDateTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    static Instant asInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
