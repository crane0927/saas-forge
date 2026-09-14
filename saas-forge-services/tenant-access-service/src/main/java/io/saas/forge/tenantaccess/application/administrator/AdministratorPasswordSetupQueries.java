package io.saas.forge.tenantaccess.application.administrator;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AdministratorPasswordSetupQueries {
    Snapshot get(UUID actor, UUID tenantId, UUID key, UUID resendId, Instant now);
    Optional<AdministratorPasswordSetupWorkflow> findRecoverable(UUID actor, UUID tenantId, UUID resendId, Instant now);
    record Snapshot(AdministratorInitializationQueries.Snapshot initialization, UUID identityId,
            AdministratorPasswordSetupWorkflow resend, AdministratorPasswordSetupWorkflow selected, boolean anyPending) { }
}
