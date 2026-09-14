package io.saas.forge.tenantaccess.application.administrator;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AdministratorPasswordSetupQueryService {
    private final AdministratorPasswordSetupQueries queries;
    private final PasswordSetupDeliveryGateway deliveries;
    private final ResendAdministratorPasswordSetupService resends;
    private final Clock clock;

    public AdministratorPasswordSetupQueryService(AdministratorPasswordSetupQueries queries,
            PasswordSetupDeliveryGateway deliveries, ResendAdministratorPasswordSetupService resends, Clock clock) {
        this.queries = queries;
        this.deliveries = deliveries;
        this.resends = resends;
        this.clock = clock;
    }

    public Progress get(UUID actor, UUID tenantId) {
        return get(actor, tenantId, null, null);
    }

    public Progress get(UUID actor, UUID tenantId, UUID key, UUID resendId) {
        if (key != null && resendId != null) throw new IllegalArgumentException("Use one resend selector");
        var snapshot = queries.get(actor, tenantId, key, resendId, clock.instant());
        var selected = snapshot.selected();
        OperationState operation = selected == null ? (key != null || resendId != null ? OperationState.UNKNOWN : OperationState.NONE)
                : selected.completed() ? OperationState.COMPLETED : OperationState.PENDING;
        if (snapshot.identityId() == null) return new Progress(tenantId, State.NOT_APPLICABLE, operation, false, false, null);
        var resend = snapshot.resend();
        var initial = snapshot.initialization().workflow();
        // 选择明确的投递请求；缺失历史工作流时不能把未查询当作未投递。
        if (resend == null && initial == null) return new Progress(tenantId, State.ACTION_REQUIRED, operation, false, false, null);
        UUID requestId = resend == null ? initial.passwordDeliveryRequestId() : resend.deliveryRequestId();
        var notification = deliveries.notification(requestId, snapshot.identityId());
        boolean pending = resend == null ? initial.passwordDeliveryPending() : !resend.completed();
        boolean exhausted = resend == null ? initial.recoveryExhaustedAt() != null : resend.recoveryExhaustedAt() != null;
        State state = switch (notification) {
            case PASSWORD_READY -> State.PASSWORD_READY;
            case RECOVERY_REQUIRED -> State.ACTION_REQUIRED;
            case MAIL_SERVICE_ACCEPTED -> State.MAIL_SERVICE_ACCEPTED;
            case PENDING, NOT_REQUESTED -> pending && !exhausted ? State.PENDING : State.ACTION_REQUIRED;
        };
        boolean canContinue = selected != null && !selected.completed() && selected.recoveryExhaustedAt() != null
                && (selected.leaseUntil() == null || !selected.leaseUntil().isAfter(clock.instant()))
                && !selected.nextAttemptAt().isAfter(clock.instant());
        boolean applicable = notification != PasswordSetupDeliveryGateway.NotificationState.PASSWORD_READY
                && notification != PasswordSetupDeliveryGateway.NotificationState.RECOVERY_REQUIRED;
        // 初始通知耗尽后可以独立重发；未决重发只能继续其原工作流。
        boolean canResend = applicable && !snapshot.anyPending() && operation != OperationState.UNKNOWN
                && (!pending || (resend == null && exhausted));
        return new Progress(tenantId, state, operation, canResend, canContinue, selected == null ? null : selected.workflowId());
    }

    /** 缺失、过期或其他操作者的恢复材料一律拒绝，绝不从恢复入口新建工作流。 */
    public void recover(UUID actor, UUID tenantId, UUID resendId, String traceId) {
        var workflow = queries.findRecoverable(actor, tenantId, resendId, clock.instant())
                .orElseThrow(() -> new AdministratorPasswordSetupException("PASSWORD_SETUP_RESEND_NOT_FOUND", "Resend not found"));
        if (!workflow.completed() && (workflow.recoveryExhaustedAt() == null
                || workflow.nextAttemptAt().isAfter(clock.instant())
                || (workflow.leaseUntil() != null && workflow.leaseUntil().isAfter(clock.instant())))) {
            throw new AdministratorPasswordSetupException("PASSWORD_SETUP_DELIVERY_PENDING", "Delivery pending", 1);
        }
        resends.recover(workflow);
    }

    public enum State { NOT_APPLICABLE, PENDING, MAIL_SERVICE_ACCEPTED, PASSWORD_READY, ACTION_REQUIRED }
    public enum OperationState { NONE, PENDING, COMPLETED, UNKNOWN }
    public record Progress(UUID tenantId, State state, OperationState operationState, boolean canResend, boolean canContinue, UUID resendId) { }
}
