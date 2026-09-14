package io.saasforge.iam.domain.session;

public record RefreshTokenConsumption(Status status, RefreshTokenFamily family) {

    public enum Status {
        CONSUMED,
        REPLAYED,
        EXPIRED,
        REVOKED,
        PURPOSE_MISMATCH,
        NOT_FOUND
    }
}
