package io.saasforge.iam.domain.session;

public record RefreshTokenFamilyContextChange(Status status, RefreshTokenFamily family) {

    public enum Status {
        CHANGED,
        UNCHANGED,
        VERSION_CONFLICT,
        NOT_FOUND
    }
}
