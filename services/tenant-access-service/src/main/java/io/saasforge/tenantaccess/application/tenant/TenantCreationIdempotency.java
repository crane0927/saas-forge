package io.saasforge.tenantaccess.application.tenant;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TenantCreationIdempotency {
    void deleteExpired(UUID callerIdentityId, UUID idempotencyKey, Instant now);

    boolean claim(UUID callerIdentityId, UUID idempotencyKey, String fingerprint, UUID tenantId, Instant expiresAt);

    Optional<Entry> find(UUID callerIdentityId, UUID idempotencyKey);

    void complete(UUID callerIdentityId, UUID idempotencyKey, TenantCreationResult result, Instant completedAt);

    record Entry(String fingerprint, UUID tenantId, TenantCreationResult result) {
        public boolean completed() {
            return result != null;
        }
    }
}
