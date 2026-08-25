package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.RevocationFence;
import io.saasforge.iam.domain.session.RevocationFenceRepository;
import io.saasforge.iam.domain.session.RevocationFenceStatus;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import io.saasforge.iam.domain.session.UserSessionFenceRelease;
import io.saasforge.iam.domain.session.UserSessionRevocationBatch;
import io.saasforge.iam.domain.session.UserSessionRevocationRepository;
import io.saasforge.iam.domain.session.UserSessionRevocationStatus;
import io.saasforge.iam.domain.session.UserSessionRevocationWorkflow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class UserSessionRevocationService {
    private final RevocationFenceRepository fences;
    private final UserSessionRevocationRepository workflows;
    private final RevocationIndex index;
    private final UserSessionRevocationTransaction transaction;
    private final UserSessionRevocationRecoveryPolicy policy;
    private final String claimant;
    private final Clock clock;

    public UserSessionRevocationService(
            RevocationFenceRepository fences,
            UserSessionRevocationRepository workflows,
            RevocationIndex index,
            UserSessionRevocationTransaction transaction,
            UserSessionRevocationRecoveryPolicy policy,
            String claimant,
            Clock clock) {
        this.fences = fences;
        this.workflows = workflows;
        this.index = index;
        this.transaction = transaction;
        this.policy = policy;
        this.claimant = claimant;
        this.clock = clock;
    }

    /** 同步调用最多取得一次租约并推进一个有界批次。 */
    public UserSessionRevocationResult revoke(UUID requestId, RevocationFenceTarget target) {
        requireUuidV7(requestId, "revocationRequestId");
        if (target == null) throw new IllegalArgumentException("撤销目标不能为空");
        UserSessionRevocationWorkflow workflow = workflows.find(requestId).orElse(null);
        if (workflow != null) {
            assertTarget(workflow.target(), target);
            UserSessionRevocationResult replay = terminalResult(workflow);
            if (replay != null) return replay;
        } else {
            workflow = transaction.prepare(requestId, target, clock.instant());
        }

        Instant now = clock.instant();
        var claimed = workflows.claim(requestId, claimant, now, now.plus(policy.leaseDuration()),
                policy.maximumAttempts());
        if (claimed.isEmpty()) {
            UserSessionRevocationWorkflow current = workflows.find(requestId).orElseThrow();
            UserSessionRevocationResult terminal = terminalResult(current);
            return terminal != null ? terminal : UserSessionRevocationResult.pending(retryAfter(current, now));
        }
        return process(claimed.orElseThrow(), true);
    }

    public void recoverNext() {
        Instant now = clock.instant();
        workflows.claimNext(claimant, now, now.plus(policy.leaseDuration()), policy.maximumAttempts())
                .ifPresent(workflow -> process(workflow, false));
    }

    /** Platform Admin 恢复入口复用原 requestId，不建立新 Fence 或跳过游标。 */
    public void recover(UUID requestId) {
        requireUuidV7(requestId, "revocationRequestId");
        UserSessionRevocationWorkflow workflow = workflows.find(requestId).orElseThrow();
        if (workflow.status() != UserSessionRevocationStatus.RECOVERY_REQUIRED) {
            throw new IllegalStateException("撤销请求不需要显式恢复");
        }
        RevocationFence fence = fences.findByRequestId(requestId).orElseThrow();
        if (fence.status() != RevocationFenceStatus.ACTIVE) {
            throw new RevocationFenceConflictException();
        }
        workflows.recover(requestId, clock.instant());
    }

    /** Redis compare-and-delete 与 PostgreSQL generation 校验处于同一调用事务。 */
    @Transactional
    public void release(
            UUID releaseRequestId, UUID revocationRequestId, RevocationFenceTarget target) {
        requireUuidV7(releaseRequestId, "releaseRequestId");
        requireUuidV7(revocationRequestId, "revocationRequestId");
        if (target == null) throw new IllegalArgumentException("释放目标不能为空");
        UserSessionFenceRelease replay = workflows.findRelease(releaseRequestId).orElse(null);
        if (replay != null) {
            if (!replay.revocationRequestId().equals(revocationRequestId) || !replay.target().equals(target)) {
                throw new RevocationFenceConflictException();
            }
            return;
        }
        UserSessionRevocationWorkflow workflow = workflows.find(revocationRequestId).orElseThrow(
                RevocationFenceConflictException::new);
        assertTarget(workflow.target(), target);
        if (workflow.status() != UserSessionRevocationStatus.COMPLETED) {
            throw new RevocationFenceConflictException();
        }
        fences.lock(target);
        RevocationFence fence = fences.findByRequestId(revocationRequestId).orElseThrow(
                RevocationFenceConflictException::new);
        if (fence.status() != RevocationFenceStatus.ACTIVE || !fence.target().equals(target)
                || !index.releaseFence(fence)) {
            throw new RevocationFenceConflictException();
        }
        Instant now = clock.instant();
        if (!fences.release(revocationRequestId, now)) {
            throw new RevocationFenceConflictException();
        }
        workflows.recordRelease(releaseRequestId, revocationRequestId, target, now);
    }

    private UserSessionRevocationResult process(UserSessionRevocationWorkflow workflow, boolean interactive) {
        try {
            Instant now = clock.instant();
            UserSessionRevocationBatch batch = workflows.loadBatch(workflow, policy.batchSize(), now);
            for (var issuance : batch.issuances()) {
                index.revokeJti(issuance.jti(), issuance.expiresAt(), now);
            }
            UserSessionRevocationWorkflow updated = transaction.commit(workflow, batch, now);
            if (updated.status() == UserSessionRevocationStatus.COMPLETED) {
                return UserSessionRevocationResult.completed(
                        updated.revokedFamilyCount(), updated.revokedJtiCount());
            }
            return UserSessionRevocationResult.pending(policy.retryDelay().toSeconds());
        } catch (RuntimeException failure) {
            handleFailure(workflow, failure);
            if (interactive) throw failure;
            return UserSessionRevocationResult.pending(policy.retryDelay().toSeconds());
        }
    }

    private void handleFailure(UserSessionRevocationWorkflow workflow, RuntimeException failure) {
        String summary = failure instanceof RevocationIndexUnavailableException
                ? RevocationIndexUnavailableException.CODE : "INTERNAL_RECOVERY_FAILURE";
        Instant now = clock.instant();
        if (workflow.attemptCount() >= policy.maximumAttempts()) {
            workflows.exhaust(workflow, now, summary);
        } else {
            workflows.scheduleRetry(workflow, now.plus(policy.retryDelay()), summary);
        }
    }

    private static UserSessionRevocationResult terminalResult(UserSessionRevocationWorkflow workflow) {
        return switch (workflow.status()) {
            case COMPLETED -> UserSessionRevocationResult.completed(
                    workflow.revokedFamilyCount(), workflow.revokedJtiCount());
            case RECOVERY_REQUIRED -> throw new UserSessionRevocationRecoveryRequiredException();
            case PENDING -> null;
        };
    }

    private static void assertTarget(RevocationFenceTarget expected, RevocationFenceTarget actual) {
        if (!expected.equals(actual)) throw new RevocationFenceConflictException();
    }

    private static long retryAfter(UserSessionRevocationWorkflow workflow, Instant now) {
        return Math.max(1, Duration.between(now, workflow.nextAttemptAt()).toSeconds());
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) throw new IllegalArgumentException(field + " 必须是 UUIDv7");
    }
}
