package io.saas.forge.tenantaccess.application.administrator;

import org.springframework.scheduling.annotation.Scheduled;

public final class TenantAdministratorInitializationWorker {
    private final InitializeTenantAdministratorService service;

    public TenantAdministratorInitializationWorker(InitializeTenantAdministratorService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${saas.forge.tenant-access.initialization.recovery-delay:PT1S}")
    public void recoverNext() {
        service.recoverNext();
    }
}
