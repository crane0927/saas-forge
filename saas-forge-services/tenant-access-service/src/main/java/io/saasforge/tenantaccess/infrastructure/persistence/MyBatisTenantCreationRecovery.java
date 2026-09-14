package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.tenant.IdempotencyKeyReusedException;
import io.saasforge.tenantaccess.application.tenant.TenantCreationRecovery;
import io.saasforge.tenantaccess.application.tenant.TenantCreationRecoveryRepository;
import io.saasforge.tenantaccess.application.tenant.TenantCreationResult;
import io.saasforge.tenantaccess.application.tenant.TenantLifecycleException;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.TenantCreationRecoveryMapper;
import io.saasforge.tenantaccess.infrastructure.persistence.record.TenantCreationRecoveryRow;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Repository
public class MyBatisTenantCreationRecovery implements TenantCreationRecoveryRepository {
    private final TenantCreationRecoveryMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final TenantListCursor cursors;

    public MyBatisTenantCreationRecovery(TenantCreationRecoveryMapper mapper, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cursors = new TenantListCursor(clock);
    }

    @Override
    public java.util.Optional<Entry> findByKey(UUID actor, UUID key) {
        return java.util.Optional.ofNullable(mapper.findByKey(new TenantCreationRecoveryMapper.ActorKey(actor, key)))
                .map(this::entry);
    }

    @Override
    public Entry prepare(UUID actor, UUID key, String name, Instant expiresAt, Instant now) {
        return transactions.execute(status -> {
            mapper.prepare(new TenantCreationRecoveryRow(null, actor, key, name, expiresAt == null ? null : expiresAt.toString(),
                    TenantAccessTime.asOffsetDateTime(now), TenantAccessTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), null));
            Entry saved = entry(mapper.findByKey(new TenantCreationRecoveryMapper.ActorKey(actor, key)));
            if (!saved.displayName().equals(name) || !Objects.equals(saved.tenantExpiresAt(), expiresAt)) {
                throw new IdempotencyKeyReusedException();
            }
            return saved;
        });
    }

    @Override
    public <T> T locked(UUID actor, UUID id, Function<Entry, T> work) {
        return transactions.execute(status -> {
            require(actor, id);
            if (!mapper.tryLock(id)) {
                throw new TenantLifecycleException("IDEMPOTENCY_REQUEST_IN_PROGRESS", "Creation is still processing", 1);
            }
            // 获锁后重新读取，避免等待前的快照覆盖刚提交的稳定结果。
            return work.apply(entry(require(actor, id)));
        });
    }

    @Override
    public void complete(Entry entry, TenantCreationResult result, Instant now) {
        if (mapper.complete(new TenantCreationRecoveryRow(entry.id(), entry.actor(), entry.key(), null, null, null,
                TenantAccessTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), json.writeValueAsString(result))) != 1) {
            throw new IllegalStateException("Could not persist Tenant creation recovery result");
        }
    }

    @Override
    public TenantCreationRecovery get(UUID actor, UUID id, Instant now) {
        return transactions.execute(status -> {
            require(actor, id);
            boolean unlocked = mapper.tryLock(id);
            Entry saved = entry(require(actor, id));
            boolean retained = now.isBefore(saved.replayUntil());
            var state = saved.result() != null ? TenantCreationRecovery.State.COMMITTED
                    : !unlocked ? TenantCreationRecovery.State.PROCESSING
                    : retained ? TenantCreationRecovery.State.NOT_COMMITTED : TenantCreationRecovery.State.UNKNOWN;
            return new TenantCreationRecovery(id, saved.displayName(), state, saved.createdAt(), saved.replayUntil(),
                    retained && unlocked, saved.result() == null ? null : saved.result().id(), retained ? saved.key() : null);
        });
    }

    @Override
    public Page list(UUID actor, String cursor, int limit, Instant now) {
        String scope = "tenant-creations:" + actor;
        var ids = mapper.list(new TenantCreationRecoveryMapper.Query(actor, cursors.decode(scope, cursor), limit + 1));
        boolean more = ids.size() > limit;
        var items = ids.stream().limit(limit).map(id -> get(actor, id, now)).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }

    private TenantCreationRecoveryRow require(UUID actor, UUID id) {
        var row = mapper.find(new TenantCreationRecoveryMapper.ActorId(actor, id));
        if (row == null) throw new TenantLifecycleException("TENANT_CREATION_NOT_FOUND", "Creation record not found; verify manually");
        return row;
    }

    private Entry entry(TenantCreationRecoveryRow row) {
        return new Entry(row.id(), row.actor(), row.key(), row.displayName(), row.tenantExpiresAt() == null ? null : Instant.parse(row.tenantExpiresAt()),
                TenantAccessTime.asInstant(row.createdAt()), TenantAccessTime.asInstant(row.replayUntil()),
                row.responseBody() == null ? null : json.readValue(row.responseBody(), TenantCreationResult.class));
    }
}
