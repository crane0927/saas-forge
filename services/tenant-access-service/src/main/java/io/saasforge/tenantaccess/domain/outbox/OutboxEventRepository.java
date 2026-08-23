package io.saasforge.tenantaccess.domain.outbox;

public interface OutboxEventRepository {
    void append(OutboxEvent event);
}
