package io.saasforge.tenantaccess.application.administrator;

import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyInvalidException;
import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.application.tenant.UuidV7Generator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

public class InitializeTenantAdministratorService {
    private final TenantAdministratorInitializationRepository workflows;
    private final IdentityProvisioningGateway identities;
    private final InitializationQuotaGateway quota;
    private final PasswordSetupDeliveryGateway passwordDeliveries;
    private final UuidV7Generator ids;
    private final Clock clock;

    public InitializeTenantAdministratorService(
            TenantAdministratorInitializationRepository workflows,
            IdentityProvisioningGateway identities,
            InitializationQuotaGateway quota,
            PasswordSetupDeliveryGateway passwordDeliveries,
            UuidV7Generator ids,
            Clock clock) {
        this.workflows = workflows;
        this.identities = identities;
        this.quota = quota;
        this.passwordDeliveries = passwordDeliveries;
        this.ids = ids;
        this.clock = clock;
    }

    /** 根工作流先于任何远程调用提交；激活提交后 Password Setup 投递失败不得回滚 Tenant。 */
    public TenantAdministratorInitializationResult initialize(
            UUID actorIdentityId,
            UUID idempotencyKey,
            UUID tenantId,
            String email,
            String displayName,
            String traceId) {
        requireUuidV7(actorIdentityId, "调用方 Identity ID");
        requireUuidV7(tenantId, "Tenant ID");
        if (idempotencyKey == null || idempotencyKey.version() != 7) {
            throw new IdempotencyKeyInvalidException();
        }
        Instant now = now();
        InitializationWorkflow workflow = workflows.prepare(new InitializationWorkflow(
                ids.next(), tenantId, actorIdentityId, idempotencyKey,
                fingerprint(tenantId, email, displayName), email, displayName,
                ids.next(), ids.next(), ids.next(), ids.next(), traceId,
                null, null, now), now);
        if (workflow.completed()) {
            replayOutcome(workflow);
            return workflow.result();
        }

        IdentityProvisioningGateway.Result identity = identities.ensure(
                workflow.identityRequestId(), workflow.administratorEmail(), workflow.administratorDisplayName());
        if (identity.credentialDisposition() == IdentityCredentialDisposition.RECOVERY_REQUIRED) {
            workflows.completeFailure(
                    workflow.tenantId(), workflow.workflowId(), "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED", now());
            throw failure("IDENTITY_CREDENTIAL_RECOVERY_REQUIRED");
        }
        try {
            quota.consume(workflow.tenantId(), workflow.consumeOperationId());
        } catch (QuotaUnavailableException exception) {
            workflows.completeFailure(workflow.tenantId(), workflow.workflowId(), exception.code(), now());
            throw failure(exception.code());
        }

        TenantAdministratorInitializationResult result = workflows.activate(
                workflow, identity.identityId(), identity.credentialDisposition(), now());
        if (identity.credentialDisposition() == IdentityCredentialDisposition.SETUP_ALLOWED) {
            try {
                passwordDeliveries.deliver(workflow.passwordDeliveryRequestId(), identity.identityId());
                workflows.completePasswordDelivery(workflow.tenantId(), workflow.workflowId(), now());
            } catch (RuntimeException ignored) {
                // 激活已提交，待投递工作项必须保留给后续恢复流程，不能反向补偿额度。
            }
        }
        return result;
    }

    private static void replayOutcome(InitializationWorkflow workflow) {
        if ("SUCCESS".equals(workflow.outcomeCode())) {
            return;
        }
        if ("IDEMPOTENCY_KEY_REUSED".equals(workflow.outcomeCode())) {
            throw new IdempotencyKeyReusedException();
        }
        throw failure(workflow.outcomeCode());
    }

    private static TenantAdministratorInitializationException failure(String code) {
        return new TenantAdministratorInitializationException(code, switch (code) {
            case "TENANT_NOT_FOUND" -> "Tenant 不存在";
            case "TENANT_EXPIRY_REACHED" -> "Tenant 绝对有效期已到达";
            case "TENANT_ALREADY_INITIALIZED" -> "Tenant 已完成管理员初始化";
            case "TENANT_ADMIN_INITIALIZATION_IN_PROGRESS" -> "Tenant 管理员初始化正在进行";
            case "IDENTITY_CREDENTIAL_RECOVERY_REQUIRED" -> "Identity 需要先完成凭据恢复";
            case "QUOTA_EXCEEDED" -> "max_users 额度不足";
            case "SUBSCRIPTION_REQUIRED" -> "Tenant 缺少有效 Subscription";
            default -> "Tenant 管理员初始化失败";
        });
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    static String fingerprint(UUID tenantId, String email, String displayName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("管理员邮箱不能为空");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "POST");
            update(digest, "/api/v1/platform/tenants/" + tenantId + "/administrator-initializations");
            update(digest, email);
            digest.update((byte) (displayName == null ? 0 : 1));
            if (displayName != null) {
                update(digest, displayName);
            }
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
