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
            Instant createdAt) {
        Long lockedContextVersion = mapper.lockFamilyContextVersion(familyId);
        if (lockedContextVersion == null || lockedContextVersion != expectedContextVersion) {
            return new TenantContextSwitchClaim(
                    TenantContextSwitchClaim.Status.FAMILY_CONTEXT_CHANGED, null);
        }
        TenantContextSwitchRow existing = mapper.findByFamilyAndKey(familyId, idempotencyKey);
        if (existing != null) {
            TenantContextSwitchWorkflow workflow = toDomain(existing);
            TenantContextSwitchClaim.Status status = workflow.sameTarget(targetFingerprint)
                    ? TenantContextSwitchClaim.Status.REPLAY
                    : TenantContextSwitchClaim.Status.TARGET_CONFLICT;
            return new TenantContextSwitchClaim(status, workflow);
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
        return new TenantContextSwitchClaim(
                TenantContextSwitchClaim.Status.CREATED, toDomain(mapper.insert(created)));
    }

    @Override
    public void complete(UUID workflowId, TenantContextSwitchStatus status, Instant completedAt) {
        if (status == TenantContextSwitchStatus.PENDING
                || status == TenantContextSwitchStatus.AWAITING_REFRESH
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESHED
                || status == TenantContextSwitchStatus.POST_SWITCH_REFRESH_REJECTED) {
            throw new IllegalArgumentException("该 Tenant Context Switch 状态不能通过通用终结操作写入");
        }
        Integer resultHttpStatus = status == TenantContextSwitchStatus.NO_OP ? 204 : null;
        if (mapper.complete(workflowId, status.name(), resultHttpStatus,
                IamTime.asOffsetDateTime(completedAt)) != 1) {
            throw new IllegalStateException("Tenant Context Switch 工作流终结失败");
        }
    }

    @Override
    public Optional<TenantContextSwitchWorkflow> findAwaitingRefresh(UUID familyId) {
        return Optional.ofNullable(mapper.findAwaitingRefreshByFamily(familyId))
                .map(MyBatisTenantContextSwitchRepository::toDomain);
    }

    @Override
    public void markAwaitingRefresh(UUID workflowId, long expectedContextVersion, Instant completedAt) {
        if (mapper.markAwaitingRefresh(
                workflowId, expectedContextVersion, IamTime.asOffsetDateTime(completedAt)) != 1) {
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
                IamTime.asInstant(row.getRefreshedAt()));
    }
}
