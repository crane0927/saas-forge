package io.saasforge.iam.domain.session;

import java.util.UUID;

public record RefreshRotation(Status status, RefreshTokenFamily family, UUID replacedAccessJti) {
    public enum Status {
        ROTATED,
        RECOVERED,
        REPLAYED,
        EXPIRED,
        REVOKED,
        PURPOSE_MISMATCH,
        NOT_FOUND
    }
}
