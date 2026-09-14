package io.saasforge.entitlement.domain.outbox;

public interface OutboxEventRepository {
    void append(OutboxEvent event);
}
