package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.shared.Sha256Digest;

/** Refresh Rotation Lease 只抑制在途不同键并发，不承载会话权威状态。 */
public interface RefreshRotationLease {
    Acquisition acquire(Sha256Digest refreshTokenDigest, Sha256Digest idempotencyKeyDigest);

    record Acquisition(boolean acquired, long retryAfterSeconds) {
    }
}
