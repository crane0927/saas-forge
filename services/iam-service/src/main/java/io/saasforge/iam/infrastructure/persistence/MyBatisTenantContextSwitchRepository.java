package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.session.TenantContextSwitchClaim;
import io.saasforge.iam.domain.session.TenantContextSwitchRepository;
import io.saasforge.iam.domain.session.TenantContextSwitchStatus;
import io.saasforge.iam.domain.session.TenantContextSwitchWorkflow;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.TenantContextSwitchMapper;
import io.saasforge.iam.infrastructure.persistence.record.TenantContextSwitchRow;
import java.time.Instant;
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
        TenantContextSwitchRow pending = mapper.findPendingByFamily(familyId);
        if (pending != null) {
            return new TenantContextSwitchClaim(
                    TenantContextSwitchClaim.Status.FAMILY_IN_PROGRESS, toDomain(pending));
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
        if (status == TenantContextSwitchStatus.PENDING) {
            throw new IllegalArgumentException("PENDING 工作流不能作为终结状态");
        }
        if (mapper.complete(workflowId, status.name(), IamTime.asOffsetDateTime(completedAt)) != 1) {
            throw new IllegalStateException("Tenant Context Switch 工作流终结失败");
        }
    }

    private static TenantContextSwitchWorkflow toDomain(TenantContextSwitchRow row) {
        return new TenantContextSwitchWorkflow(
                row.getId(), row.getFamilyId(), row.getIdempotencyKey(), row.getTargetMembershipId(),
                Sha256Digest.of(row.getTargetFingerprint()), row.getExpectedContextVersion(),
                TenantContextSwitchStatus.valueOf(row.getSwitchStatus()),
                IamTime.asInstant(row.getCreatedAt()), IamTime.asInstant(row.getCompletedAt()));
    }
}
