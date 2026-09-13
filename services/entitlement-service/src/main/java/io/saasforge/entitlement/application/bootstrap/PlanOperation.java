package io.saasforge.entitlement.application.bootstrap;

import java.time.Instant;
import java.util.UUID;

/** 原操作者私有结果；NOT_COMMITTED 仅允许继续原操作，不授权新 Key。 */
public record PlanOperation(UUID id, Operation operation, State state, Instant createdAt,
        Instant replayUntil, boolean canReplay, UUID planId, UUID idempotencyKey, PlanResult result, String code) {
    public enum Operation { CREATE, ACTIVATE }
    public enum State { COMMITTED, PROCESSING, NOT_COMMITTED, UNKNOWN }
}
