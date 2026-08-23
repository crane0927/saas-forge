package io.saasforge.tenantaccess.application.administrator;

import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyInvalidException;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

public class ResendAdministratorPasswordSetupService {
    private final AdministratorPasswordSetupRepository workflows;
    private final PasswordSetupDeliveryGateway deliveries;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final InitializationRecoveryPolicy recoveryPolicy;
    private final String claimant;

    public ResendAdministratorPasswordSetupService(
            AdministratorPasswordSetupRepository workflows,
            PasswordSetupDeliveryGateway deliveries,
            UuidV7Generator ids,
            Clock clock,
            InitializationRecoveryPolicy recoveryPolicy,
            String claimant) {
        this.workflows = workflows;
        this.deliveries = deliveries;
        this.ids = ids;
        this.clock = clock;
        this.recoveryPolicy = recoveryPolicy;
        this.claimant = claimant;
    }

    /** 根工作流先于 IAM 调用提交；调用方只能通过 Tenant 路径触发权威初始管理员的投递。 */
    public void resend(
            UUID actorIdentityId, UUID idempotencyKey, UUID tenantId, String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(tenantId, "Tenant ID");
        if (idempotencyKey == null || idempotencyKey.version() != 7) {
            throw new IdempotencyKeyInvalidException();
        }
        Instant now = now();
        AdministratorPasswordSetupWorkflow workflow = workflows.prepare(
                new AdministratorPasswordSetupWorkflow(
                        ids.next(), tenantId, actorIdentityId, idempotencyKey, fingerprint(tenantId),
                        null, ids.next(), traceId, null, now, 0, now,
                        null, null, null, null),
                now);
        if (workflow.completed()) {
            replayOutcome(workflow);
            return;
        }
        AdministratorPasswordSetupWorkflow claimed = workflows.claim(
                        workflow.workflowId(), claimant, now, now.plus(recoveryPolicy.leaseDuration()))
                .orElseThrow(() -> pending(1));
        deliver(claimed, true);
    }

    public boolean recoverNext() {
        Instant now = now();
        var claimed = workflows.claimNext(claimant, now, now.plus(recoveryPolicy.leaseDuration()));
        if (claimed.isEmpty()) {
            return false;
        }
        try {
            deliver(claimed.orElseThrow(), false);
        } catch (RuntimeException ignored) {
            // 失败诊断与下一次恢复时间已持久化，单个请求不能中断调度线程。
        }
        return true;
    }

    private void deliver(AdministratorPasswordSetupWorkflow workflow, boolean synchronous) {
        try {
            deliveries.deliver(workflow.deliveryRequestId(), workflow.administratorIdentityId());
            workflows.completeSuccess(workflow, now());
        } catch (IdentityCredentialRecoveryRequiredException exception) {
            try {
                workflows.completeRecoveryRequired(workflow, now());
            } catch (RuntimeException persistenceFailure) {
                long retryAfter = scheduleRetry(workflow, persistenceFailure);
                if (synchronous) {
                    throw pending(retryAfter);
                }
                return;
            }
            if (synchronous) {
                throw recoveryRequired();
            }
        } catch (RuntimeException exception) {
            long retryAfter = scheduleRetry(workflow, exception);
            if (synchronous) {
                throw pending(retryAfter);
            }
        }
    }

    private long scheduleRetry(AdministratorPasswordSetupWorkflow workflow, RuntimeException exception) {
        Instant failedAt = now();
        if (recoveryPolicy.automaticRecoveryExhausted(workflow.attemptCount())) {
            workflows.exhaustRecovery(workflow, failedAt, exception.getClass().getSimpleName());
            return 1;
        }
        Duration delay = recoveryPolicy.retryDelay(workflow.attemptCount());
        workflows.scheduleRetry(workflow, failedAt.plus(delay), exception.getClass().getSimpleName());
        return Math.max(1, delay.toSeconds());
    }

    private static void replayOutcome(AdministratorPasswordSetupWorkflow workflow) {
        if (!workflow.completed()) {
            return;
        }
        if ("SUCCESS".equals(workflow.outcomeCode())) {
            return;
        }
        if ("IDENTITY_CREDENTIAL_RECOVERY_REQUIRED".equals(workflow.outcomeCode())) {
            throw recoveryRequired();
        }
        throw new AdministratorPasswordSetupException(
                workflow.outcomeCode(), "Tenant 管理员 Password Setup 重发失败");
    }

    private static AdministratorPasswordSetupException pending(long retryAfterSeconds) {
        return new AdministratorPasswordSetupException(
                "PASSWORD_SETUP_DELIVERY_PENDING", "Password Setup 邮件投递尚未完成", retryAfterSeconds);
    }

    private static AdministratorPasswordSetupException recoveryRequired() {
        return new AdministratorPasswordSetupException(
                "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", "Identity 需要先完成凭据恢复");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    static String fingerprint(UUID tenantId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "POST");
            update(digest, "/api/v1/platform/tenants/" + tenantId + "/administrator-password-setups");
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}
