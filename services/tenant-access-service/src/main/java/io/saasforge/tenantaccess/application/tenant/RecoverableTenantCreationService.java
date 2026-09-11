package io.saasforge.tenantaccess.application.tenant;

import io.saasforge.tenantaccess.domain.tenant.Tenant;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class RecoverableTenantCreationService {
    private final CreatePendingTenantService creation;
    private final TenantCreationRecoveryRepository recovery;
    private final Clock clock;

    public RecoverableTenantCreationService(CreatePendingTenantService creation,
            TenantCreationRecoveryRepository recovery, Clock clock) {
        this.creation = creation;
        this.recovery = recovery;
        this.clock = clock;
    }

    public TenantCreationResult create(UUID actor, UUID key, String name, Instant expiresAt, String traceId) {
        if (actor == null || actor.version() != 7) throw new IllegalArgumentException("Invalid actor");
        if (key == null || key.version() != 7) throw new IdempotencyKeyInvalidException();
        // 格式校验不登记业务操作；已登记请求的恢复永远使用其原始字段。
        if (name == null || name.isBlank() || name.length() > 200) throw new IllegalArgumentException("Invalid Tenant name");
        if (recovery.findByKey(actor, key).isEmpty()) {
            Tenant.pending(key, name, expiresAt, now());
        }
        var entry = recovery.prepare(actor, key, name, expiresAt, now());
        return recovery.locked(actor, entry.id(), saved -> submit(saved, traceId));
    }

    public TenantCreationRecovery get(UUID actor, UUID id) { return recovery.get(actor, id, now()); }

    public TenantCreationRecoveryRepository.Page list(UUID actor, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
        return recovery.list(actor, cursor, limit, now());
    }

    public TenantCreationRecovery recover(UUID actor, UUID id, UUID key, String traceId) {
        recovery.locked(actor, id, saved -> {
            if (!saved.key().equals(key)) throw new IdempotencyKeyReusedException();
            submit(saved, traceId);
            return null;
        });
        return get(actor, id);
    }

    private TenantCreationResult submit(TenantCreationRecoveryRepository.Entry saved, String traceId) {
        if (!now().isBefore(saved.replayUntil())) {
            throw new TenantLifecycleException("TENANT_CREATION_RECOVERY_EXPIRED", "Replay retention ended; verify the result manually");
        }
        if (saved.result() != null) return saved.result();
        TenantCreationResult result = creation.create(saved.actor(), saved.key(), saved.displayName(),
                saved.tenantExpiresAt(), traceId);
        recovery.complete(saved, result, now());
        return result;
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MILLIS); }
}
