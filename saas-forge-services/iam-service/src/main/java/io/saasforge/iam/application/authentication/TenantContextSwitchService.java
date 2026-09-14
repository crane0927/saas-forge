package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchClaim;
import io.saasforge.iam.domain.session.TenantContextSwitchRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchStatus;
import io.saasforge.iam.domain.session.TenantContextSwitchWorkflow;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class TenantContextSwitchService {
    private final RefreshTokenFamilyRepository families;
    private final TenantContextSwitchRepository workflows;
    private final MembershipValidation memberships;
    private final RefreshTokenIssuer refreshTokens;
    private final TenantContextSwitchTransaction transaction;
    private final TenantContextSwitchRecoveryPolicy recoveryPolicy;
    private final String claimant;
    private final Clock clock;

    public TenantContextSwitchService(
            RefreshTokenFamilyRepository families,
            TenantContextSwitchRepository workflows,
            MembershipValidation memberships,
            RefreshTokenIssuer refreshTokens,
            TenantContextSwitchTransaction transaction,
            TenantContextSwitchRecoveryPolicy recoveryPolicy,
            String claimant,
            Clock clock) {
        this.families = families;
        this.workflows = workflows;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.transaction = transaction;
        this.recoveryPolicy = recoveryPolicy;
        this.claimant = claimant;
        this.clock = clock;
    }

    public void switchContext(UUID idempotencyKey, String refreshTokenValue, UUID targetMembershipId) {
        switchContext(idempotencyKey, refreshTokenValue, targetMembershipId, null);
    }

    public void switchContext(
            UUID idempotencyKey, String refreshTokenValue, UUID targetMembershipId, String traceId) {
        requireUuidV7(idempotencyKey, "Idempotency-Key");
        requireUuidV7(targetMembershipId, "membershipId");
        Instant inspectedAt = clock.instant();
        Sha256Digest refreshTokenDigest = refreshDigest(refreshTokenValue);
        RefreshTokenFamily family = families.findUsableByTokenDigest(refreshTokenDigest, inspectedAt)
                .filter(candidate -> candidate.purpose() == RefreshTokenFamilyPurpose.USER_TENANT)
                .orElseThrow(TenantContextSwitchSessionInvalidException::new);
        Sha256Digest targetFingerprint = digest(targetMembershipId.toString());
        TenantContextSwitchClaim claim = workflows.claim(
                family.id(), family.contextVersion(), idempotencyKey,
                targetMembershipId, targetFingerprint, inspectedAt, claimant,
                inspectedAt.plus(recoveryPolicy.leaseDuration()), recoveryPolicy.maximumAttempts());
        TenantContextSwitchWorkflow workflow = claim.workflow();
        switch (claim.status()) {
            case TARGET_CONFLICT -> throw TenantContextSwitchConflictException.idempotencyConflict();
            case FAMILY_IN_PROGRESS -> throw TenantContextSwitchConflictException.inProgress();
            case FAMILY_REFRESH_REQUIRED -> throw TenantContextSwitchConflictException.refreshRequired();
            case FAMILY_CONTEXT_CHANGED -> throw new TenantContextSwitchSessionInvalidException();
            case RECOVERY_EXHAUSTED -> throw TenantContextSwitchConflictException.retryRequired();
            case REPLAY -> {
                replay(workflow);
                if (workflow.status() != TenantContextSwitchStatus.PENDING) {
                    return;
                }
                throw pending(workflow);
            }
            case CREATED, RECOVERY_CLAIMED -> {
                // 新工作流继续执行下面的权威校验。
            }
        }

        process(workflow, traceId, true);
    }

    public void recoverNext() {
        Instant now = clock.instant();
        workflows.claimNext(
                        claimant, now, now.plus(recoveryPolicy.leaseDuration()), recoveryPolicy.maximumAttempts())
                .ifPresent(workflow -> process(workflow, null, false));
    }

    private void process(TenantContextSwitchWorkflow workflow, String traceId, boolean interactive) {
        RefreshTokenFamily family;
        try {
            family = families.findById(workflow.familyId())
                    .filter(candidate -> candidate.purpose() == RefreshTokenFamilyPurpose.USER_TENANT)
                    .filter(candidate -> candidate.contextVersion() == workflow.expectedContextVersion())
                    .filter(candidate -> candidate.isUsableAt(clock.instant()))
                    .orElseThrow(() -> new IllegalStateException("Tenant Context Switch Family 不可恢复"));
        } catch (RuntimeException exception) {
            handleFailure(workflow, exception, interactive);
            return;
        }

        Optional<ValidatedMembership> current;
        try {
            current = memberships.validate(family.identityId(), family.membershipId());
        } catch (RuntimeException exception) {
            handleFailure(workflow, exception, interactive);
            return;
        }
        if (current.isEmpty() || !family.tenantId().equals(current.orElseThrow().tenantId())) {
            try {
                transaction.rejectCurrent(workflow, clock.instant());
            } catch (RuntimeException exception) {
                handleFailure(workflow, exception, interactive);
                return;
            }
            if (interactive) {
                throw TenantContextSwitchAccessRejectedException.currentMembership();
            }
            return;
        }
        Optional<ValidatedMembership> target;
        try {
            target = memberships.validate(family.identityId(), workflow.targetMembershipId());
        } catch (RuntimeException exception) {
            handleFailure(workflow, exception, interactive);
            return;
        }
        if (target.isEmpty()) {
            try {
                transaction.complete(workflow, TenantContextSwitchStatus.TARGET_REJECTED, clock.instant());
            } catch (RuntimeException exception) {
                handleFailure(workflow, exception, interactive);
                return;
            }
            if (interactive) {
                throw TenantContextSwitchAccessRejectedException.targetMembership();
            }
            return;
        }
        if (workflow.targetMembershipId().equals(family.membershipId())) {
            try {
                transaction.complete(workflow, TenantContextSwitchStatus.NO_OP, clock.instant());
            } catch (RuntimeException exception) {
                handleFailure(workflow, exception, interactive);
            }
            return;
        }
        try {
            transaction.switchContext(
                    workflow, family, workflow.expectedContextVersion(),
                    workflow.targetMembershipId(), target.orElseThrow().tenantId(), clock.instant(), traceId);
        } catch (AccessContextUnavailableException exception) {
            try {
                transaction.complete(workflow, TenantContextSwitchStatus.TARGET_REJECTED, clock.instant());
            } catch (RuntimeException completionFailure) {
                handleFailure(workflow, completionFailure, interactive);
                return;
            }
            if (interactive) {
                throw TenantContextSwitchAccessRejectedException.targetMembership();
            }
        } catch (RuntimeException exception) {
            handleFailure(workflow, exception, interactive);
        }
    }

    private void handleFailure(
            TenantContextSwitchWorkflow workflow, RuntimeException failure, boolean interactive) {
        Instant failedAt = clock.instant();
        String failureSummary = failureSummary(failure);
        Duration retryDelay = recoveryPolicy.retryDelay(workflow.attemptCount());
        if (recoveryPolicy.exhausted(workflow.attemptCount())) {
            workflows.exhaustRecovery(workflow, failedAt, failureSummary);
        } else {
            workflows.scheduleRetry(workflow, failedAt.plus(retryDelay), failureSummary);
        }
        if (interactive) {
            throw new TenantContextSwitchPendingException(Math.max(1, retryDelay.toSeconds()));
        }
    }

    private TenantContextSwitchPendingException pending(TenantContextSwitchWorkflow workflow) {
        long seconds = Math.max(1, Duration.between(clock.instant(), workflow.nextAttemptAt()).toSeconds());
        return new TenantContextSwitchPendingException(seconds);
    }

    private static String failureSummary(RuntimeException failure) {
        if (failure instanceof TenantAccessUnavailableException) {
            return TenantAccessUnavailableException.CODE;
        }
        if (failure instanceof RevocationIndexUnavailableException) {
            return RevocationIndexUnavailableException.CODE;
        }
        return "INTERNAL_RECOVERY_FAILURE";
    }

    private static void replay(TenantContextSwitchWorkflow workflow) {
        switch (workflow.status()) {
            case NO_OP -> {
                return;
            }
            case CURRENT_REJECTED -> throw TenantContextSwitchAccessRejectedException.currentMembership();
            case TARGET_REJECTED -> throw TenantContextSwitchAccessRejectedException.targetMembership();
            case PENDING -> {
                // PENDING 只能在取得恢复租约后继续，避免同 Key 请求绕过 Worker 租约并发执行。
            }
            case AWAITING_REFRESH, POST_SWITCH_REFRESHED, POST_SWITCH_REFRESH_REJECTED -> {
                return;
            }
        }
    }

    private Sha256Digest refreshDigest(String refreshTokenValue) {
        try {
            return refreshTokens.digest(refreshTokenValue);
        } catch (RuntimeException invalidToken) {
            throw new TenantContextSwitchSessionInvalidException();
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }

    private static Sha256Digest digest(String value) {
        try {
            return Sha256Digest.of(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }
}
