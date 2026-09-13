package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saasforge.entitlement.application.subscription.SubscriptionOperation;
import io.saasforge.entitlement.application.subscription.SubscriptionRecoveryRepository;
import io.saasforge.entitlement.application.subscription.InitialSubscriptionResult;
import io.saasforge.entitlement.application.subscription.SubscriptionRecoveryException;
import io.saasforge.entitlement.infrastructure.persistence.mapper.SubscriptionRecoveryMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.SubscriptionRecoveryRow;
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
public class MyBatisSubscriptionRecovery implements SubscriptionRecoveryRepository {
    private final SubscriptionRecoveryMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final QuotaDefinitionCursor cursors;

    public MyBatisSubscriptionRecovery(SubscriptionRecoveryMapper mapper, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cursors = new QuotaDefinitionCursor(clock);
    }

    @Override
    public java.util.Optional<Entry> findByKey(UUID actor, UUID key) {
        return java.util.Optional.ofNullable(mapper.findByKey(new SubscriptionRecoveryMapper.ActorKey(actor, key)))
                .map(this::entry);
    }

    @Override
    public Entry prepare(UUID actor, UUID key, io.saasforge.entitlement.application.subscription.SubscriptionDraft draft, Instant now) {
        return transactions.execute(status -> {
            mapper.prepare(new SubscriptionRecoveryRow(null, actor, key, draft.tenantId(), draft.planId(),
                    draft.endsAt() == null ? null : draft.endsAt().toString(), EntitlementTime.asOffsetDateTime(now),
                    EntitlementTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), null));
            Entry saved = entry(mapper.findByKey(new SubscriptionRecoveryMapper.ActorKey(actor, key)));
            if (!Objects.equals(saved.draft(), draft)) throw new IdempotencyKeyReusedException();
            return saved;
        });
    }

    @Override
    public <T> T locked(UUID actor, UUID id, Function<Entry, T> work) {
        return transactions.execute(status -> {
            require(actor, id);
            if (!mapper.tryLock(id)) {
                throw new io.saasforge.entitlement.application.bootstrap.IdempotencyRequestInProgressException();
            }
            // 获锁后重新读取，避免等待前的快照覆盖刚提交的稳定结果。
            return work.apply(entry(require(actor, id)));
        });
    }

    @Override
    public void complete(Entry entry, InitialSubscriptionResult result, Instant now) {
        if (mapper.complete(new SubscriptionRecoveryRow(entry.id(), entry.actor(), entry.key(), null, null, null, null,
                EntitlementTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), json.writeValueAsString(result))) != 1) {
            throw new IllegalStateException("Could not persist Subscription recovery result");
        }
    }

    @Override
    public SubscriptionOperation get(UUID actor, UUID id, Instant now) {
        return transactions.execute(status -> {
            require(actor, id);
            boolean unlocked = mapper.tryLock(id);
            Entry saved = entry(require(actor, id));
            boolean retained = now.isBefore(saved.replayUntil());
            if (!saved.draft().tenantId().toString().equals(mapper.setTenant(saved.draft().tenantId()))) {
                throw new IllegalStateException("Subscription recovery Tenant scope unavailable");
            }
            var state = saved.result() != null ? SubscriptionOperation.State.COMMITTED
                    : !unlocked ? SubscriptionOperation.State.PROCESSING
                    : retained ? SubscriptionOperation.State.NOT_COMMITTED : SubscriptionOperation.State.UNKNOWN;
            return new SubscriptionOperation(id, saved.draft().tenantId(), state, saved.createdAt(), saved.replayUntil(),
                    retained && unlocked && (saved.result() != null || mapper.canExecute(id)),
                    saved.result() == null ? null : saved.result().id(), retained ? saved.key() : null);
        });
    }

    @Override
    public Page list(UUID actor, UUID tenantId, String cursor, int limit, Instant now) {
        String scope = "subscription-operations:" + actor + ":" + tenantId;
        var ids = mapper.list(new SubscriptionRecoveryMapper.Query(actor, tenantId, cursors.decode(scope, cursor), limit + 1));
        boolean more = ids.size() > limit;
        var items = ids.stream().limit(limit).map(id -> get(actor, id, now)).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }

    private SubscriptionRecoveryRow require(UUID actor, UUID id) {
        var row = mapper.find(new SubscriptionRecoveryMapper.ActorId(actor, id));
        if (row == null) throw new SubscriptionRecoveryException("SUBSCRIPTION_OPERATION_NOT_FOUND");
        return row;
    }

    private Entry entry(SubscriptionRecoveryRow row) {
        return new Entry(row.id(), row.actor(), row.key(),
                new io.saasforge.entitlement.application.subscription.SubscriptionDraft(row.tenantId(), row.planId(),
                        row.endsAt() == null ? null : Instant.parse(row.endsAt())),
                EntitlementTime.asInstant(row.createdAt()), EntitlementTime.asInstant(row.replayUntil()),
                row.responseBody() == null ? null : json.readValue(row.responseBody(), InitialSubscriptionResult.class));
    }
}
