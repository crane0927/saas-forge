package io.saasforge.iam.application.authentication;

public record UserSessionRevocationResult(
        Status status, long retryAfterSeconds, long revokedFamilyCount, long revokedJtiCount) {
    public enum Status { PENDING, COMPLETED }

    public static UserSessionRevocationResult pending(long retryAfterSeconds) {
        return new UserSessionRevocationResult(Status.PENDING, Math.max(1, retryAfterSeconds), 0, 0);
    }

    public static UserSessionRevocationResult completed(long familyCount, long jtiCount) {
        return new UserSessionRevocationResult(Status.COMPLETED, 0, familyCount, jtiCount);
    }
}
