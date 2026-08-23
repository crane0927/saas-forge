package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saasforge.tenantaccess.application.administrator.InitializationWorkflow;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationRepository;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializedEventFactory;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantAdministratorInitializationMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantAdministratorInitializationRow;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantRow;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Repository
public final class MyBatisTenantAdministratorInitializationRepository
        implements TenantAdministratorInitializationRepository {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final TenantAdministratorInitializationMapper mapper;
    private final OutboxEventRepository outboxEvents;
    private final TenantAdministratorInitializedEventFactory eventFactory;
    private final UuidV7Generator ids;
    private final ObjectMapper objectMapper;

    public MyBatisTenantAdministratorInitializationRepository(
            TenantAdministratorInitializationMapper mapper,
            OutboxEventRepository outboxEvents,
            TenantAdministratorInitializedEventFactory eventFactory,
            UuidV7Generator ids,
            ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.ids = ids;
        this.objectMapper = objectMapper;
    }

    /** Tenant 行锁同时串行化新 Key；根记录提交后远程步骤才可开始。 */
    @Override
    @Transactional
    public InitializationWorkflow prepare(InitializationWorkflow candidate, Instant now) {
        setTarget(candidate.tenantId());
        TenantRow tenant = mapper.lockTenant(candidate.tenantId());
        if (tenant == null) {
            throw failure("TENANT_NOT_FOUND");
        }
        mapper.deleteExpiredWorkflow(candidate.actorIdentityId(), candidate.idempotencyKey(),
                TenantAccessTime.asOffsetDateTime(now));

        String terminalOutcome = null;
        if (!"PENDING".equals(tenant.status())) {
            terminalOutcome = "TENANT_ALREADY_INITIALIZED";
        } else if (!TenantAccessTime.asInstant(tenant.expiresAt()).isAfter(now)) {
            terminalOutcome = "TENANT_EXPIRY_REACHED";
        }
        TenantAdministratorInitializationRow row = toRow(candidate, terminalOutcome, now);
        if (mapper.insertWorkflow(row) == 1) {
            return fromRow(row);
        }
        TenantAdministratorInitializationRow existing = mapper.findWorkflow(
                candidate.actorIdentityId(), candidate.idempotencyKey());
        if (existing == null) {
            throw failure("TENANT_ADMIN_INITIALIZATION_IN_PROGRESS");
        }
        if (!existing.requestFingerprint().equals(candidate.requestFingerprint())) {
            throw new IdempotencyKeyReusedException();
        }
        return fromRow(existing);
    }

    @Override
    @Transactional
    public void completeFailure(UUID tenantId, UUID workflowId, String outcomeCode, Instant completedAt) {
        setTarget(tenantId);
        InitializationWorkflow workflow = lock(workflowId);
        if (workflow.completed()) {
            return;
        }
        if (mapper.completeFailure(
                workflowId, outcomeCode, failureStatus(outcomeCode), TenantAccessTime.asOffsetDateTime(completedAt),
                TenantAccessTime.asOffsetDateTime(completedAt.plus(IDEMPOTENCY_RETENTION))) != 1) {
            throw new IllegalStateException("Tenant Admin 初始化失败结果保存失败");
        }
    }

    /** Membership、系统角色、初始管理员关系、激活、稳定响应、工作项与 Outbox 共享事务。 */
    @Override
    @Transactional
    public TenantAdministratorInitializationResult activate(
            InitializationWorkflow workflow,
            UUID administratorIdentityId,
            IdentityCredentialDisposition credentialDisposition,
            Instant activatedAt) {
        setTarget(workflow.tenantId());
        InitializationWorkflow locked = lock(workflow.workflowId());
        if (locked.completed()) {
            if ("SUCCESS".equals(locked.outcomeCode())) {
                return locked.result();
            }
            throw failure(locked.outcomeCode());
        }
        TenantRow tenant = mapper.lockTenant(workflow.tenantId());
        if (tenant == null) {
            throw failure("TENANT_NOT_FOUND");
        }
        if (!"PENDING".equals(tenant.status())) {
            throw failure("TENANT_ALREADY_INITIALIZED");
        }
        if (!TenantAccessTime.asInstant(tenant.expiresAt()).isAfter(activatedAt)) {
            throw failure("TENANT_EXPIRY_REACHED");
        }

        UUID membershipCandidate = ids.next();
        mapper.insertMembership(membershipCandidate, workflow.tenantId(), administratorIdentityId);
        UUID membershipId = requireId(
                mapper.findMembershipId(workflow.tenantId(), administratorIdentityId), "Membership");
        if (mapper.enableMembership(membershipId) != 1) {
            throw new IllegalStateException("Tenant Admin Membership 启用失败");
        }

        UUID roleCandidate = ids.next();
        mapper.insertAdministratorRole(
                roleCandidate, workflow.tenantId(), TenantAccessTime.asOffsetDateTime(activatedAt));
        UUID roleId = requireId(mapper.findAdministratorRoleId(workflow.tenantId()), "Tenant Administrator Role");
        mapper.insertRoleAssignment(
                workflow.tenantId(), membershipId, roleId, TenantAccessTime.asOffsetDateTime(activatedAt));
        mapper.insertInitialAdministrator(
                workflow.tenantId(), membershipId, TenantAccessTime.asOffsetDateTime(activatedAt));
        UUID initialMembershipId = requireId(
                mapper.findInitialAdministratorMembershipId(workflow.tenantId()), "Initial Tenant Administrator");
        if (!membershipId.equals(initialMembershipId)) {
            throw new IllegalStateException("Tenant 初始管理员关系不可替换");
        }
        if (mapper.activateTenant(workflow.tenantId(), TenantAccessTime.asOffsetDateTime(activatedAt)) != 1) {
            throw new IllegalStateException("Tenant 激活失败");
        }
        if (credentialDisposition == IdentityCredentialDisposition.SETUP_ALLOWED) {
            mapper.insertPasswordDeliveryWorkItem(
                    workflow.workflowId(), workflow.tenantId(), administratorIdentityId,
                    workflow.passwordDeliveryRequestId(), TenantAccessTime.asOffsetDateTime(activatedAt));
        }

        TenantAdministratorInitializationResult result = new TenantAdministratorInitializationResult(
                workflow.tenantId(), tenant.displayName(), TenantStatus.ACTIVE,
                TenantAccessTime.asInstant(tenant.expiresAt()), TenantAccessTime.asInstant(tenant.createdAt()), activatedAt);
        if (mapper.completeSuccess(
                workflow.workflowId(), objectMapper.writeValueAsString(result),
                TenantAccessTime.asOffsetDateTime(activatedAt),
                TenantAccessTime.asOffsetDateTime(activatedAt.plus(IDEMPOTENCY_RETENTION))) != 1) {
            throw new IllegalStateException("Tenant Admin 初始化稳定结果保存失败");
        }
        outboxEvents.append(eventFactory.create(
                workflow.tenantId(), membershipId, administratorIdentityId, roleId,
                workflow.actorIdentityId(), activatedAt, workflow.traceId()));
        return result;
    }

    @Override
    @Transactional
    public void completePasswordDelivery(UUID tenantId, UUID workflowId, Instant completedAt) {
        setTarget(tenantId);
        mapper.completePasswordDelivery(workflowId, TenantAccessTime.asOffsetDateTime(completedAt));
    }

    private InitializationWorkflow lock(UUID workflowId) {
        TenantAdministratorInitializationRow row = mapper.lockWorkflow(workflowId);
        if (row == null) {
            throw new IllegalStateException("Tenant Admin 初始化根工作流不存在");
        }
        return fromRow(row);
    }

    private void setTarget(UUID tenantId) {
        if (!tenantId.toString().equals(mapper.setOperationTarget(tenantId))) {
            throw new IllegalStateException("Tenant Operation Target 设置失败");
        }
    }

    private TenantAdministratorInitializationRow toRow(
            InitializationWorkflow workflow, String terminalOutcome, Instant now) {
        Instant completedAt = terminalOutcome == null ? null : now;
        return new TenantAdministratorInitializationRow(
                workflow.workflowId(), workflow.tenantId(), workflow.actorIdentityId(), workflow.idempotencyKey(),
                workflow.requestFingerprint(), workflow.administratorEmail(), workflow.administratorDisplayName(),
                workflow.identityRequestId(), workflow.consumeOperationId(), workflow.releaseOperationId(),
                workflow.passwordDeliveryRequestId(), workflow.traceId(), terminalOutcome,
                terminalOutcome == null ? null : failureStatus(terminalOutcome), null,
                TenantAccessTime.asOffsetDateTime(workflow.createdAt()),
                completedAt == null ? null : TenantAccessTime.asOffsetDateTime(completedAt),
                completedAt == null ? null : TenantAccessTime.asOffsetDateTime(completedAt.plus(IDEMPOTENCY_RETENTION)));
    }

    private InitializationWorkflow fromRow(TenantAdministratorInitializationRow row) {
        return new InitializationWorkflow(
                row.workflowId(), row.tenantId(), row.actorIdentityId(), row.idempotencyKey(),
                row.requestFingerprint(), row.administratorEmail(), row.administratorDisplayName(),
                row.identityRequestId(), row.consumeOperationId(), row.releaseOperationId(),
                row.passwordDeliveryRequestId(), row.traceId(), row.outcomeCode(),
                row.responseBody() == null
                        ? null
                        : objectMapper.readValue(row.responseBody(), TenantAdministratorInitializationResult.class),
                TenantAccessTime.asInstant(row.createdAt()));
    }

    private static UUID requireId(UUID id, String type) {
        if (id == null) {
            throw new IllegalStateException(type + " 保存失败");
        }
        return id;
    }

    private static int failureStatus(String outcomeCode) {
        return "TENANT_NOT_FOUND".equals(outcomeCode) ? 404 : 409;
    }

    private static TenantAdministratorInitializationException failure(String code) {
        return new TenantAdministratorInitializationException(code, code);
    }
}
