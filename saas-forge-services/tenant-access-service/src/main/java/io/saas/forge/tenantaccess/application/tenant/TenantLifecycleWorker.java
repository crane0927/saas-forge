package io.saas.forge.tenantaccess.application.tenant;

import org.springframework.scheduling.annotation.Scheduled;

public final class TenantLifecycleWorker {
    private final TenantLifecycleService service;

    public TenantLifecycleWorker(TenantLifecycleService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${saas.forge.tenant-access.lifecycle.recovery-delay:PT1S}")
    public void recoverNext() {
        service.recoverNext();
    }
}
