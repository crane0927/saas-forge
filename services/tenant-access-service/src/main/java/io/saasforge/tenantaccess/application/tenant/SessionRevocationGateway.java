package io.saasforge.tenantaccess.application.tenant;

import java.util.UUID;

public interface SessionRevocationGateway {
    Result revoke(UUID revocationRequestId, UUID tenantId);

    void recover(UUID revocationRequestId, UUID tenantId);

    void release(UUID releaseRequestId, UUID revocationRequestId, UUID tenantId);

    record Result(Status status, long retryAfterSeconds, long revokedFamilyCount, long revokedJtiCount) {
        public enum Status { PENDING, COMPLETED }

        public static Result pending(long retryAfterSeconds) {
            return new Result(Status.PENDING, Math.max(1, retryAfterSeconds), 0, 0);
        }

        public static Result completed(long revokedFamilyCount, long revokedJtiCount) {
            return new Result(Status.COMPLETED, 0, revokedFamilyCount, revokedJtiCount);
        }
    }
}
