package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupException;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupRepository;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupWorkflow;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.AdministratorPasswordSetupMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.AdministratorPasswordSetupWorkflowRow;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisAdministratorPasswordSetupRepository
        implements AdministratorPasswordSetupRepository {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final AdministratorPasswordSetupMapper mapper;

    public MyBatisAdministratorPasswordSetupRepository(AdministratorPasswordSetupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public AdministratorPasswordSetupWorkflow prepare(
            AdministratorPasswordSetupWorkflow candidate, Instant now) {
        setTarget(candidate.tenantId());
        if (mapper.lockTenant(candidate.tenantId()) == null) {
            throw failure("TENANT_NOT_FOUND");
        }
        UUID identityId = mapper.findInitialAdministratorIdentityId(candidate.tenantId());
        if (identityId == null) {
            throw failure("TENANT_INITIAL_ADMINISTRATOR_NOT_FOUND");
        }
        mapper.deleteExpiredWorkflow(
                candidate.actorIdentityId(), candidate.idempotencyKey(), TenantAccessTime.asOffsetDateTime(now));
        AdministratorPasswordSetupWorkflowRow row = toRow(candidate, identityId);
        if (mapper.insertWorkflow(row) == 1) {
            return fromRow(row);
        }
        AdministratorPasswordSetupWorkflowRow existing = mapper.findWorkflow(
                candidate.actorIdentityId(), candidate.idempotencyKey());
        if (existing == null) {
            throw new IllegalStateException("Password Setup 重发幂等记录读取失败");
        }
        if (!existing.requestFingerprint().equals(candidate.requestFingerprint())) {
            throw new IdempotencyKeyReusedException();
        }
        return fromRow(existing);
    }

    @Override
    @Transactional
    public Optional<AdministratorPasswordSetupWorkflow> claim(
            UUID workflowId, String claimant, Instant now, Instant claimedUntil) {
        UUID claimed = mapper.claimWorkflow(
                workflowId, claimant, TenantAccessTime.asOffsetDateTime(now),
                TenantAccessTime.asOffsetDateTime(claimedUntil));
        return claimed == null ? Optional.empty() : Optional.of(lock(claimed));
    }

    @Override
    @Transactional
    public Optional<AdministratorPasswordSetupWorkflow> claimNext(
            String claimant, Instant now, Instant claimedUntil) {
        UUID claimed = mapper.claimNextWorkflow(
                claimant, TenantAccessTime.asOffsetDateTime(now),
                TenantAccessTime.asOffsetDateTime(claimedUntil));
        return claimed == null ? Optional.empty() : Optional.of(lock(claimed));
    }

    @Override
    @Transactional
    public void scheduleRetry(
            AdministratorPasswordSetupWorkflow workflow, Instant retryAt, String failureSummary) {
        setTarget(workflow.tenantId());
        if (mapper.scheduleRetry(
                workflow.workflowId(), workflow.leaseOwner(), workflow.attemptCount(),
                TenantAccessTime.asOffsetDateTime(retryAt), failureSummary) != 1) {
            throw staleLease();
        }
    }

    @Override
    @Transactional
    public void exhaustRecovery(
            AdministratorPasswordSetupWorkflow workflow, Instant exhaustedAt, String failureSummary) {
        setTarget(workflow.tenantId());
        if (mapper.exhaustRecovery(
                workflow.workflowId(), workflow.leaseOwner(), workflow.attemptCount(),
                TenantAccessTime.asOffsetDateTime(exhaustedAt), failureSummary) != 1) {
            throw staleLease();
        }
    }

    @Override
    @Transactional
    public void completeSuccess(AdministratorPasswordSetupWorkflow workflow, Instant completedAt) {
        complete(workflow, "SUCCESS", completedAt);
    }

    @Override
    @Transactional
    public void completeRecoveryRequired(
            AdministratorPasswordSetupWorkflow workflow, Instant completedAt) {
        complete(workflow, "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", completedAt);
    }

    private void complete(
            AdministratorPasswordSetupWorkflow workflow, String outcomeCode, Instant completedAt) {
        setTarget(workflow.tenantId());
        if (mapper.completeOutcome(
                workflow.workflowId(), workflow.leaseOwner(), workflow.attemptCount(), outcomeCode,
                TenantAccessTime.asOffsetDateTime(completedAt),
                TenantAccessTime.asOffsetDateTime(completedAt.plus(IDEMPOTENCY_RETENTION))) != 1) {
            throw staleLease();
        }
    }

    private AdministratorPasswordSetupWorkflow lock(UUID workflowId) {
        AdministratorPasswordSetupWorkflowRow row = mapper.lockWorkflow(workflowId);
        if (row == null) {
            throw new IllegalStateException("Password Setup 重发工作流不存在");
        }
        return fromRow(row);
    }

    private void setTarget(UUID tenantId) {
        mapper.setOperationTarget(tenantId);
    }

    private static AdministratorPasswordSetupWorkflowRow toRow(
            AdministratorPasswordSetupWorkflow workflow, UUID identityId) {
        return new AdministratorPasswordSetupWorkflowRow(
                workflow.workflowId(), workflow.tenantId(), workflow.actorIdentityId(), workflow.idempotencyKey(),
                workflow.requestFingerprint(), identityId, workflow.deliveryRequestId(), workflow.traceId(),
                workflow.outcomeCode(), TenantAccessTime.asOffsetDateTime(workflow.createdAt()), null, null,
                workflow.attemptCount(), TenantAccessTime.asOffsetDateTime(workflow.nextAttemptAt()),
                workflow.leaseOwner(), TenantAccessTime.asOffsetDateTime(workflow.leaseUntil()),
                TenantAccessTime.asOffsetDateTime(workflow.recoveryExhaustedAt()), workflow.lastFailure());
    }

    private static AdministratorPasswordSetupWorkflow fromRow(
            AdministratorPasswordSetupWorkflowRow row) {
        return new AdministratorPasswordSetupWorkflow(
                row.workflowId(), row.tenantId(), row.actorIdentityId(), row.idempotencyKey(),
                row.requestFingerprint(), row.administratorIdentityId(), row.deliveryRequestId(), row.traceId(),
                row.outcomeCode(), TenantAccessTime.asInstant(row.createdAt()), row.attemptCount(),
                TenantAccessTime.asInstant(row.nextAttemptAt()), row.leaseOwner(),
                TenantAccessTime.asInstant(row.leaseUntil()), TenantAccessTime.asInstant(row.recoveryExhaustedAt()),
                row.lastFailure());
    }

    private static AdministratorPasswordSetupException failure(String code) {
        return new AdministratorPasswordSetupException(code, switch (code) {
            case "TENANT_NOT_FOUND" -> "Tenant 不存在";
            case "TENANT_INITIAL_ADMINISTRATOR_NOT_FOUND" -> "Tenant 尚未建立初始管理员关系";
            default -> "Tenant 管理员 Password Setup 重发失败";
        });
    }

    private static IllegalStateException staleLease() {
        return new IllegalStateException("Password Setup 重发工作流租约已失效");
    }
}
