package io.saasforge.iam.application.authentication;

import org.springframework.scheduling.annotation.Scheduled;

public final class UserSessionRevocationWorker {
    private final UserSessionRevocationService service;

    public UserSessionRevocationWorker(UserSessionRevocationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${saasforge.iam.session-revocation.worker-delay:PT1S}")
    public void recoverNext() {
        service.recoverNext();
    }
}
