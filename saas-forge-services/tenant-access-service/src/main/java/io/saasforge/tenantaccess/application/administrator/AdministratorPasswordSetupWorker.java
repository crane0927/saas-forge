package io.saasforge.tenantaccess.application.administrator;

import org.springframework.scheduling.annotation.Scheduled;

public final class AdministratorPasswordSetupWorker {
    private final ResendAdministratorPasswordSetupService service;

    public AdministratorPasswordSetupWorker(ResendAdministratorPasswordSetupService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${saasforge.tenant-access.password-setup.recovery-delay:PT1S}")
    public void recoverNext() {
        service.recoverNext();
    }
}
