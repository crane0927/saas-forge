package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionOperation;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionRecoveryRepository;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionResult;
import io.saasforge.entitlement.application.bootstrap.QuotaDefinitionRecoveryException;
import io.saasforge.entitlement.infrastructure.persistence.mapper.QuotaDefinitionRecoveryMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.QuotaDefinitionRecoveryRow;
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
public class MyBatisQuotaDefinitionRecovery implements QuotaDefinitionRecoveryRepository {
    private final QuotaDefinitionRecoveryMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final QuotaDefinitionCursor cursors;

    public MyBatisQuotaDefinitionRecovery(QuotaDefinitionRecoveryMapper mapper, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cursors = new QuotaDefinitionCursor(clock);
    }

    @Override
    public Entry prepare(UUID actor, UUID key, QuotaDefinitionOperation.Operation operation, UUID targetId, Instant now) {
        return transactions.execute(status -> {
            mapper.prepare(new QuotaDefinitionRecoveryRow(null, actor, key, operation.name(), targetId,
                    EntitlementTime.asOffsetDateTime(now), EntitlementTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), null));
            Entry saved = entry(mapper.findByKey(new QuotaDefinitionRecoveryMapper.ActorKey(actor, key)));
            if (saved.operation() != operation || !Objects.equals(saved.targetId(), targetId)) {
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
                throw new io.saasforge.entitlement.application.bootstrap.IdempotencyRequestInProgressException();
            }
            // 获锁后重新读取，避免等待前的快照覆盖刚提交的稳定结果。
            return work.apply(entry(require(actor, id)));
        });
    }

    @Override
    public void complete(Entry entry, QuotaDefinitionResult result, Instant now) {
        if (mapper.complete(new QuotaDefinitionRecoveryRow(entry.id(), entry.actor(), entry.key(), null, null, null,
                EntitlementTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), json.writeValueAsString(result))) != 1) {
            throw new IllegalStateException("Could not persist Quota Definition recovery result");
        }
    }

    @Override
    public QuotaDefinitionOperation get(UUID actor, UUID id, Instant now) {
        return transactions.execute(status -> {
            require(actor, id);
            boolean unlocked = mapper.tryLock(id);
            Entry saved = entry(require(actor, id));
            boolean retained = now.isBefore(saved.replayUntil());
            var state = saved.result() != null ? QuotaDefinitionOperation.State.COMMITTED
                    : !unlocked ? QuotaDefinitionOperation.State.PROCESSING
                    : retained ? QuotaDefinitionOperation.State.NOT_COMMITTED : QuotaDefinitionOperation.State.UNKNOWN;
            return new QuotaDefinitionOperation(id, saved.operation(), state, saved.createdAt(), saved.replayUntil(),
                    retained && unlocked && (saved.result() != null || mapper.canExecute(id)), saved.result() == null ? saved.targetId() : saved.result().id(), retained ? saved.key() : null);
        });
    }

    @Override
    public Page list(UUID actor, String cursor, int limit, Instant now) {
        String scope = "quota-definition-operations:" + actor;
        var ids = mapper.list(new QuotaDefinitionRecoveryMapper.Query(actor, cursors.decode(scope, cursor), limit + 1));
        boolean more = ids.size() > limit;
        var items = ids.stream().limit(limit).map(id -> get(actor, id, now)).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }

    private QuotaDefinitionRecoveryRow require(UUID actor, UUID id) {
        var row = mapper.find(new QuotaDefinitionRecoveryMapper.ActorId(actor, id));
        if (row == null) throw new QuotaDefinitionRecoveryException("QUOTA_DEFINITION_OPERATION_NOT_FOUND");
        return row;
    }

    private Entry entry(QuotaDefinitionRecoveryRow row) {
        return new Entry(row.id(), row.actor(), row.key(), QuotaDefinitionOperation.Operation.valueOf(row.operation()), row.targetId(),
                EntitlementTime.asInstant(row.createdAt()), EntitlementTime.asInstant(row.replayUntil()),
                row.responseBody() == null ? null : json.readValue(row.responseBody(), QuotaDefinitionResult.class));
    }
}
