package io.saasforge.tenantaccess.application.administrator;

import java.util.UUID;

public interface PasswordSetupDeliveryGateway {
    void deliver(UUID requestId, UUID identityId);
}
