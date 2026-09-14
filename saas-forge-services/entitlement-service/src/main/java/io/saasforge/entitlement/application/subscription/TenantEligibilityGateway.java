package io.saasforge.entitlement.application.subscription;

import java.util.UUID;

public interface TenantEligibilityGateway {
    enum Outcome {
        PENDING_ELIGIBLE,
        NOT_FOUND,
        INVALID_STATE,
        EXPIRY_REACHED
    }

    Outcome checkInitialSubscription(UUID tenantId);
}
