package io.saas.forge.entitlement.domain.outbox;

public interface OutboxEventRepository {
    void append(OutboxEvent event);
}
