package io.saasforge.tenantaccess.application.administrator;

import java.util.UUID;

public interface InitializationQuotaGateway {
    void consume(UUID tenantId, UUID operationId);

    void release(UUID tenantId, UUID operationId);
}
