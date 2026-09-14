package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

public class CreatePendingTenantService {
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);

    private final TenantRepository tenants;
    private final TenantCreationIdempotency idempotency;
    private final OutboxEventRepository outboxEvents;
    private final TenantCreatedEventFactory eventFactory;
    private final UuidV7Generator ids;
    private final Clock clock;

    public CreatePendingTenantService(
            TenantRepository tenants,
            TenantCreationIdempotency idempotency,
            OutboxEventRepository outboxEvents,
            TenantCreatedEventFactory eventFactory,
            UuidV7Generator ids,
            Clock clock) {
        this.tenants = tenants;
        this.idempotency = idempotency;
        this.outboxEvents = outboxEvents;
        this.eventFactory = eventFactory;
        this.ids = ids;
        this.clock = clock;
    }

    /** Tenant、稳定响应和 Outbox 必须共享事务，并在首次 Tenant 写入前设置权威操作目标。 */
    @Transactional
    public TenantCreationResult create(
            UUID callerIdentityId,
            UUID idempotencyKey,
            String displayName,
            Instant expiresAt,
            String traceId) {
        requireUuidV7(callerIdentityId, "调用方 Identity ID");
        if (idempotencyKey == null || idempotencyKey.version() != 7) {
            throw new IdempotencyKeyInvalidException();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        UUID tenantId = ids.next();
        Tenant tenant = Tenant.pending(tenantId, displayName, expiresAt, now);
        String fingerprint = fingerprint(displayName, expiresAt);

        idempotency.deleteExpired(callerIdentityId, idempotencyKey, now);
        if (!idempotency.claim(
                callerIdentityId, idempotencyKey, fingerprint, tenantId, now.plus(IDEMPOTENCY_RETENTION))) {
            TenantCreationIdempotency.Entry existing = idempotency.find(callerIdentityId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("幂等记录并发状态不可见"));
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyReusedException();
            }
            if (!existing.completed()) {
                throw new IllegalStateException("幂等请求尚未完成");
            }
            return existing.result();
        }

        tenants.setOperationTarget(tenantId);
        tenants.create(tenant);
        TenantCreationResult result = new TenantCreationResult(
                tenant.id(), tenant.displayName(), tenant.status(), tenant.expiresAt(), tenant.createdAt(), tenant.updatedAt());
        idempotency.complete(callerIdentityId, idempotencyKey, result, now);
        outboxEvents.append(eventFactory.create(tenant, callerIdentityId, now, traceId));
        return result;
    }

    private static String fingerprint(String displayName, Instant expiresAt) {
        if (displayName == null) {
            throw new IllegalArgumentException("Tenant displayName 不能为空");
        }
        String canonical = "POST\n/api/v1/platform/tenants\n" + displayName + "\n" + expiresAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static void requireUuidV7(UUID value, String field) {
        if (value == null || value.version() != 7) {
            throw new IllegalArgumentException(field + " 必须是 UUIDv7");
        }
    }
}
