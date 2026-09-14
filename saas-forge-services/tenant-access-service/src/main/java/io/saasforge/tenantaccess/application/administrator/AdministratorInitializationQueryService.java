package io.saasforge.tenantaccess.application.administrator;

import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdministratorInitializationQueryService {
    private final AdministratorInitializationQueries queries;
    private final InitializeTenantAdministratorService initialization;
    private final Clock clock;

    public AdministratorInitializationQueryService(AdministratorInitializationQueries queries,
            InitializeTenantAdministratorService initialization, Clock clock) {
        this.queries = queries;
        this.initialization = initialization;
        this.clock = clock;
    }

    public Progress get(UUID actor, UUID tenantId) {
        return project(actor, queries.get(tenantId));
    }

    /** 身份来自当前授权；请求与原 Key 仅从服务端恢复，恢复入口不能重新创建根工作流。 */
    public TenantAdministratorInitializationResult recover(UUID actor, UUID tenantId, UUID initializationId,
            String traceId) {
        var snapshot = queries.get(tenantId);
        var workflow = snapshot.workflow();
        if (workflow == null || !workflow.workflowId().equals(initializationId)
                || !workflow.actorIdentityId().equals(actor)) {
            throw new TenantAdministratorInitializationException(
                    "TENANT_ADMIN_INITIALIZATION_NOT_FOUND", "Initialization not found");
        }
        if ("SUCCESS".equals(workflow.outcomeCode())) return workflow.result();
        if (!project(actor, snapshot).canContinue()) {
            throw new TenantAdministratorInitializationException(
                    "TENANT_ADMIN_INITIALIZATION_IN_PROGRESS", "Explicit recovery is not available");
        }
        return initialization.initialize(actor, workflow.idempotencyKey(), tenantId,
                workflow.administratorEmail(), workflow.administratorDisplayName(), traceId);
    }

    private Progress project(UUID actor, AdministratorInitializationQueries.Snapshot snapshot) {
        var workflow = snapshot.workflow();
        var tenant = snapshot.tenant();
        boolean eligible = tenant.status() == TenantStatus.PENDING
                && (tenant.expiresAt() == null || tenant.expiresAt().isAfter(clock.instant()));
        if (snapshot.initialMembershipId() != null) {
            return new Progress(tenant.id(), workflow == null ? null : workflow.workflowId(), State.SUCCEEDED,
                    false, false, snapshot.initialMembershipId(), null);
        }
        if (workflow == null) {
            return new Progress(tenant.id(), null, State.NOT_STARTED, eligible, false, null, null);
        }
        State state;
        if ("TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED".equals(workflow.outcomeCode())) {
            state = State.RETRY_REQUIRED;
        } else if (workflow.completed()) {
            state = State.FAILED;
        } else if (workflow.recoveryExhaustedAt() != null) {
            state = State.RECOVERY_REQUIRED;
        } else if (workflow.state() == InitializationWorkflowState.COMPENSATING) {
            state = State.COMPENSATING;
        } else {
            state = State.PROCESSING;
        }
        // 自动执行期间只读进度；耗尽后的显式继续仍受原 actor 和实时租约约束。
        boolean canContinue = state == State.RECOVERY_REQUIRED && workflow.actorIdentityId().equals(actor)
                && (workflow.leaseUntil() == null || !workflow.leaseUntil().isAfter(clock.instant()))
                && !workflow.nextAttemptAt().isAfter(clock.instant());
        return new Progress(tenant.id(), workflow.workflowId(), state,
                eligible && state == State.RETRY_REQUIRED, canContinue, null, workflow.outcomeCode());
    }

    public enum State { NOT_STARTED, PROCESSING, RECOVERY_REQUIRED, COMPENSATING, RETRY_REQUIRED, SUCCEEDED, FAILED }

    public record Progress(UUID tenantId, UUID initializationId, State state, boolean canStart,
            boolean canContinue, UUID initialAdministratorMembershipId, String failureCode) { }
}
