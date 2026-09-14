package io.saasforge.iam.domain.session;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserSessionRevocationRepository {
    Optional<UserSessionRevocationWorkflow> find(UUID revocationRequestId);

    UserSessionRevocationWorkflow create(UUID revocationRequestId, RevocationFenceTarget target, Instant at);

    Optional<UserSessionRevocationWorkflow> claim(
            UUID revocationRequestId, String claimant, Instant now, Instant leaseUntil, int maximumAttempts);

    Optional<UserSessionRevocationWorkflow> claimNext(
            String claimant, Instant now, Instant leaseUntil, int maximumAttempts);

    UserSessionRevocationBatch loadBatch(
            UserSessionRevocationWorkflow workflow, int batchSize, Instant at);

    UserSessionRevocationWorkflow commitBatch(
            UserSessionRevocationWorkflow workflow,
            UserSessionRevocationBatch batch,
            Instant at);

    void scheduleRetry(UserSessionRevocationWorkflow workflow, Instant retryAt, String failureSummary);

    void exhaust(UserSessionRevocationWorkflow workflow, Instant at, String failureSummary);

    void recover(UUID revocationRequestId, Instant at);

    Optional<UserSessionFenceRelease> findRelease(UUID releaseRequestId);

    void recordRelease(UUID releaseRequestId, UUID revocationRequestId, RevocationFenceTarget target, Instant at);
}
