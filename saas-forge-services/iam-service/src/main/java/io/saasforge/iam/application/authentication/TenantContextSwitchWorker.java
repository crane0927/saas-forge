package io.saasforge.iam.application.authentication;

import org.springframework.scheduling.annotation.Scheduled;

public final class TenantContextSwitchWorker {
    private final TenantContextSwitchService service;

    public TenantContextSwitchWorker(TenantContextSwitchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${saasforge.iam.tenant-context-switch.recovery-delay:PT1S}")
    public void recoverNext() {
        service.recoverNext();
    }
}
