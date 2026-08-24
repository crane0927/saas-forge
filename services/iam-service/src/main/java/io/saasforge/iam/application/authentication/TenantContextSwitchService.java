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
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class TenantContextSwitchService {
    private static final long RETRY_AFTER_SECONDS = 1;

    private final RefreshTokenFamilyRepository families;
    private final TenantContextSwitchRepository workflows;
    private final MembershipValidation memberships;
    private final RefreshTokenIssuer refreshTokens;
    private final TenantContextSwitchTransaction transaction;
    private final Clock clock;

    public TenantContextSwitchService(
            RefreshTokenFamilyRepository families,
            TenantContextSwitchRepository workflows,
            MembershipValidation memberships,
            RefreshTokenIssuer refreshTokens,
            TenantContextSwitchTransaction transaction,
            Clock clock) {
        this.families = families;
        this.workflows = workflows;
        this.memberships = memberships;
        this.refreshTokens = refreshTokens;
        this.transaction = transaction;
        this.clock = clock;
    }

    /**
     * 真实上下文变更由后续执行切片接管；本入口只在双重权威校验后保留 PENDING 根工作流。
     */
    public void switchContext(UUID idempotencyKey, String refreshTokenValue, UUID targetMembershipId) {
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
                targetMembershipId, targetFingerprint, inspectedAt);
        TenantContextSwitchWorkflow workflow = claim.workflow();
        switch (claim.status()) {
            case TARGET_CONFLICT -> throw TenantContextSwitchConflictException.idempotencyConflict();
            case FAMILY_IN_PROGRESS -> throw TenantContextSwitchConflictException.inProgress();
            case FAMILY_CONTEXT_CHANGED -> throw new TenantContextSwitchSessionInvalidException();
            case REPLAY -> {
                replay(workflow);
                if (workflow.status() == TenantContextSwitchStatus.NO_OP) {
                    return;
                }
            }
            case CREATED -> {
                // 新工作流继续执行下面的权威校验。
            }
        }

        Optional<ValidatedMembership> current = memberships.validate(family.identityId(), family.membershipId());
        if (current.isEmpty() || !family.tenantId().equals(current.orElseThrow().tenantId())) {
            transaction.rejectCurrent(workflow.id(), refreshTokenDigest, clock.instant());
            throw TenantContextSwitchAccessRejectedException.currentMembership();
        }
        Optional<ValidatedMembership> target = memberships.validate(family.identityId(), targetMembershipId);
        if (target.isEmpty()) {
            transaction.complete(workflow.id(), TenantContextSwitchStatus.TARGET_REJECTED, clock.instant());
            throw TenantContextSwitchAccessRejectedException.targetMembership();
        }
        if (targetMembershipId.equals(family.membershipId())) {
            transaction.complete(workflow.id(), TenantContextSwitchStatus.NO_OP, clock.instant());
            return;
        }
        throw new TenantContextSwitchPendingException(RETRY_AFTER_SECONDS);
    }

    private static void replay(TenantContextSwitchWorkflow workflow) {
        switch (workflow.status()) {
            case NO_OP -> {
                return;
            }
            case CURRENT_REJECTED -> throw TenantContextSwitchAccessRejectedException.currentMembership();
            case TARGET_REJECTED -> throw TenantContextSwitchAccessRejectedException.targetMembership();
            case PENDING -> {
                // PENDING 会继续同步重试权威校验，不把允许结果缓存为授权事实。
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
