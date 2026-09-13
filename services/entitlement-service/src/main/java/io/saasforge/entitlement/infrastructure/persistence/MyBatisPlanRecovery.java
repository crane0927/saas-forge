package io.saasforge.entitlement.infrastructure.persistence;

import io.saasforge.entitlement.application.bootstrap.IdempotencyKeyReusedException;
import io.saasforge.entitlement.application.bootstrap.PlanOperation;
import io.saasforge.entitlement.application.bootstrap.PlanRecoveryRepository;
import io.saasforge.entitlement.application.bootstrap.PlanResult;
import io.saasforge.entitlement.application.bootstrap.PlanRecoveryException;
import io.saasforge.entitlement.infrastructure.persistence.mapper.PlanRecoveryMapper;
import io.saasforge.entitlement.infrastructure.persistence.record.PlanRecoveryRow;
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
public class MyBatisPlanRecovery implements PlanRecoveryRepository {
    private final PlanRecoveryMapper mapper;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final QuotaDefinitionCursor cursors;

    public MyBatisPlanRecovery(PlanRecoveryMapper mapper, ObjectMapper json,
            PlatformTransactionManager manager, Clock clock) {
        this.mapper = mapper;
        this.json = json;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cursors = new QuotaDefinitionCursor(clock);
    }

    @Override
    public Entry prepare(UUID actor, UUID key, PlanOperation.Operation operation, UUID targetId, io.saasforge.entitlement.application.bootstrap.PlanDraft draft, Instant now) {
        return transactions.execute(status -> {
            mapper.prepare(new PlanRecoveryRow(null, actor, key, operation.name(), targetId,
                    EntitlementTime.asOffsetDateTime(now), EntitlementTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), null, draft == null ? null : json.writeValueAsString(draft)));
            Entry saved = entry(mapper.findByKey(new PlanRecoveryMapper.ActorKey(actor, key)));
            if (saved.operation() != operation || !Objects.equals(saved.targetId(), targetId) || !Objects.equals(saved.draft(), draft)) {
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
    public void complete(Entry entry, PlanResult result, Instant now) {
        if (mapper.complete(new PlanRecoveryRow(entry.id(), entry.actor(), entry.key(), null, null, null,
                EntitlementTime.asOffsetDateTime(now.plus(Duration.ofHours(24))), json.writeValueAsString(result), null)) != 1) {
            throw new IllegalStateException("Could not persist Plan recovery result");
        }
    }

    @Override
    public PlanOperation get(UUID actor, UUID id, Instant now) {
        return transactions.execute(status -> {
            require(actor, id);
            boolean unlocked = mapper.tryLock(id);
            Entry saved = entry(require(actor, id));
            boolean retained = now.isBefore(saved.replayUntil());
            var state = saved.result() != null ? PlanOperation.State.COMMITTED
                    : !unlocked ? PlanOperation.State.PROCESSING
                    : retained ? PlanOperation.State.NOT_COMMITTED : PlanOperation.State.UNKNOWN;
            return new PlanOperation(id, saved.operation(), state, saved.createdAt(), saved.replayUntil(),
                    retained && unlocked && (saved.result() != null || mapper.canExecute(id)), saved.result() == null ? saved.targetId() : saved.result().id(), retained ? saved.key() : null, saved.result(), saved.draft() == null ? null : saved.draft().code());
        });
    }

    @Override
    public Page list(UUID actor, String cursor, int limit, Instant now) {
        String scope = "plan-operations:" + actor;
        var ids = mapper.list(new PlanRecoveryMapper.Query(actor, cursors.decode(scope, cursor), limit + 1));
        boolean more = ids.size() > limit;
        var items = ids.stream().limit(limit).map(id -> get(actor, id, now)).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }

    private PlanRecoveryRow require(UUID actor, UUID id) {
        var row = mapper.find(new PlanRecoveryMapper.ActorId(actor, id));
        if (row == null) throw new PlanRecoveryException("PLAN_OPERATION_NOT_FOUND");
        return row;
    }

    private Entry entry(PlanRecoveryRow row) {
        return new Entry(row.id(), row.actor(), row.key(), PlanOperation.Operation.valueOf(row.operation()), row.targetId(),
                row.requestBody() == null ? null : json.readValue(row.requestBody(), io.saasforge.entitlement.application.bootstrap.PlanDraft.class),
                EntitlementTime.asInstant(row.createdAt()), EntitlementTime.asInstant(row.replayUntil()),
                row.responseBody() == null ? null : json.readValue(row.responseBody(), PlanResult.class));
    }
}
