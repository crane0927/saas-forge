package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.session.TenantContextSwitchClaim;
import io.saasforge.iam.domain.session.TenantContextSwitchRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchStatus;
import io.saasforge.iam.domain.session.TenantContextSwitchWorkflow;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.TenantContextSwitchMapper;
import io.saasforge.iam.infrastructure.persistence.record.TenantContextSwitchRow;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisTenantContextSwitchRepository implements TenantContextSwitchRepository {
    private static final String ATTEMPT_LIMIT_REACHED = "RECOVERY_ATTEMPT_LIMIT_REACHED";

    private final TenantContextSwitchMapper mapper;

    public MyBatisTenantContextSwitchRepository(TenantContextSwitchMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public TenantContextSwitchClaim claim(
            UUID familyId,
            long expectedContextVersion,
            UUID idempotencyKey,
            UUID targetMembershipId,
            Sha256Digest targetFingerprint,
            Instant createdAt,
            String claimant,
            Instant claimedUntil,
            int maximumAttempts) {
        Long lockedContextVersion = mapper.lockFamilyContextVersion(familyId);
        if (lockedContextVersion == null) {
            return new TenantContextSwitchClaim(
                    TenantContextSwitchClaim.Status.FAMILY_CONTEXT_CHANGED, null);
        }
        TenantContextSwitchRow existing = mapper.findByFamilyAndKey(familyId, idempotencyKey);
        if (existing != null) {
            TenantContextSwitchWorkflow workflow = toDomain(existing);
            if (!workflow.sameTarget(targetFingerprint)) {
                return new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.TARGET_CONFLICT, workflow);
            }
            if (workflow.recoveryExhausted()) {
                return new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.RECOVERY_EXHAUSTED, workflow);
            }
            if (mapper.exhaustWorkflowAtLimit(
                    workflow.id(), IamTime.asOffsetDateTime(createdAt), maximumAttempts,
                    ATTEMPT_LIMIT_REACHED) == 1) {
                return new TenantContextSwitchClaim(
                        TenantContextSwitchClaim.Status.RECOVERY_EXHAUSTED,
                        toDomain(mapper.findById(workflow.id())));
            }
            TenantContextSwitchRow claimed = mapper.claimExisting(
                    workflow.id(), claimant, IamTime.asOffsetDateTime(createdAt),
                    IamTime.asOffsetDateTime(claimedUntil), maximumAttempts);
            return claimed == null
                    ? new TenantContextSwitchClaim(TenantContextSwitchClaim.Status.REPLAY, workflow)
                    : new TenantContextSwitchClaim(
                            TenantContextSwitchClaim.Status.RECOVERY_CLAIMED, toDomain(claimed));
        }
        if (lockedContextVersion != expectedContextVersion) {
            return new TenantContextSwitchClaim(
                    TenantContextSwitchClaim.Status.FAMILY_CONTEXT_CHANGED, null);
        }
        TenantContextSwitchRow blocking = mapper.findBlockingByFamily(familyId);
        if (blocking != null) {
            TenantContextSwitchWorkflow workflow = toDomain(blocking);
            TenantContextSwitchClaim.Status status = workflow.status() == TenantContextSwitchStatus.AWAITING_REFRESH
                    ? TenantContextSwitchClaim.Status.FAMILY_REFRESH_REQUIRED
                    : TenantContextSwitchClaim.Status.FAMILY_IN_PROGRESS;
            return new TenantContextSwitchClaim(
                    status, workflow);
        }
        TenantContextSwitchRow created = new TenantContextSwitchRow();
        created.setFamilyId(familyId);
        created.setIdempotencyKey(idempotencyKey);
        created.setTargetMembershipId(targetMembershipId);
        created.setTargetFingerprint(targetFingerprint.value());
        created.setExpectedContextVersion(expectedContextVersion);
        created.setSwitchStatus(TenantContextSwitchStatus.PENDING.name());
        created.setCreatedAt(IamTime.asOffsetDateTime(createdAt));
        created.setAttemptCount(1);
        created.setNextAttemptAt(IamTime.asOffsetDateTime(createdAt));
        created.setLeaseOwner(claimant);
        created.setLeaseUntil(IamTime.asOffsetDateTime(claimedUntil));
        return new TenantContextSwitchClaim(
                TenantContextSwitchClaim.Status.CREATED, toDomain(mapper.insert(created)));
    }

    @Override
    @Transactional
    public Optional<TenantContextSwitchWorkflow> claimNext(
            String claimant, Instant now, Instant claimedUntil, int maximumAttempts) {
        mapper.exhaustExpiredAtLimit(
                IamTime.asOffsetDateTime(now), maximumAttempts, ATTEMPT_LIMIT_REACHED);
        TenantContextSwitchRow candidate = mapper.findNextClaimable(
                IamTime.asOffsetDateTime(now), maximumAttempts);
        if (candidate == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.claimExisting(
                        candidate.getId(), claimant, IamTime.asOffsetDateTime(now),
                        IamTime.asOffsetDateTime(claimedUntil), maximumAttempts))
                .map(MyBatisTenantContextSwitchRepository::toDomain);
    }

    @Override
    public Optional<TenantContextSwitchWorkflow> findById(UUID workflowId) {
        return Optional.ofNullable(mapper.findById(workflowId))
                .map(MyBatisTenantContextSwitchRepository::toDomain);
    }

    @Override
    public void complete(
            TenantContextSwitchWorkflow workflow, TenantContextSwitchStatus status, Instant completedAt) {
        if (status == TenantContextSwitchStatus.PENDING
                || status == TenantContextSwitchStatus.AWAITING_REFRESH
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESHED
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESH_REJECTED) {
            throw new IllegalArgumentException("该 Tenant Context Switch 状态不能通过通用终结操作写入");
        }
        Integer resultHttpStatus = status == TenantContextSwitchStatus.NO_OP ? 204 : null;
        if (mapper.complete(workflow.id(), status.name(), resultHttpStatus, workflow.attemptCount(),
                IamTime.asOffsetDateTime(completedAt)) != 1) {
            throw new IllegalStateException("Tenant Context Switch 工作流终结失败");
        }
    }

    @Override
    public boolean scheduleRetry(
            TenantContextSwitchWorkflow workflow, Instant retryAt, String failureSummary) {
        return mapper.scheduleRetry(
                workflow.id(), workflow.leaseOwner(), workflow.attemptCount(),
                IamTime.asOffsetDateTime(retryAt), failureSummary) == 1;
    }

    @Override
    public boolean exhaustRecovery(
            TenantContextSwitchWorkflow workflow, Instant exhaustedAt, String failureSummary) {
        return mapper.exhaustRecovery(
                workflow.id(), workflow.leaseOwner(), workflow.attemptCount(),
                IamTime.asOffsetDateTime(exhaustedAt), failureSummary) == 1;
    }

    @Override
    public Optional<TenantContextSwitchWorkflow> findAwaitingRefresh(UUID familyId) {
        return Optional.ofNullable(mapper.findAwaitingRefreshByFamily(familyId))
                .map(MyBatisTenantContextSwitchRepository::toDomain);
    }

    @Override
    public void markAwaitingRefresh(
            TenantContextSwitchWorkflow workflow, long expectedContextVersion, Instant completedAt) {
        if (mapper.markAwaitingRefresh(
                workflow.id(), expectedContextVersion, workflow.attemptCount(),
                IamTime.asOffsetDateTime(completedAt)) != 1) {
            throw new IllegalStateException("Tenant Context Switch 等待 Refresh 状态保存失败");
        }
    }

    @Override
    public void completePostSwitchRefresh(
            UUID familyId, long contextVersion, boolean authorized, Instant refreshedAt) {
        TenantContextSwitchStatus status = authorized
                ? TenantContextSwitchStatus.POST_SWITCH_REFRESHED
                : TenantContextSwitchStatus.POST_SWITCH_REFRESH_REJECTED;
        if (mapper.completePostSwitchRefresh(
                familyId, contextVersion, status.name(), IamTime.asOffsetDateTime(refreshedAt)) != 1) {
            throw new IllegalStateException("Tenant Context Switch 的 post-switch Refresh 状态保存失败");
        }
    }

    private static TenantContextSwitchWorkflow toDomain(TenantContextSwitchRow row) {
        return new TenantContextSwitchWorkflow(
                row.getId(), row.getFamilyId(), row.getIdempotencyKey(), row.getTargetMembershipId(),
                Sha256Digest.of(row.getTargetFingerprint()), row.getExpectedContextVersion(),
                TenantContextSwitchStatus.valueOf(row.getSwitchStatus()), row.getResultHttpStatus(),
                IamTime.asInstant(row.getCreatedAt()), IamTime.asInstant(row.getCompletedAt()),
                IamTime.asInstant(row.getRefreshedAt()), row.getAttemptCount(),
                IamTime.asInstant(row.getNextAttemptAt()), row.getLeaseOwner(),
                IamTime.asInstant(row.getLeaseUntil()), IamTime.asInstant(row.getRecoveryExhaustedAt()),
                row.getLastFailure());
    }
}
