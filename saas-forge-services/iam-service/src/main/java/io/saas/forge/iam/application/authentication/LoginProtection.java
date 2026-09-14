package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.identity.NormalizedEmail;

public interface LoginProtection {
    boolean isLocked(NormalizedEmail email);

    void recordCredentialFailure(NormalizedEmail email);

    void clearCredentialFailures(NormalizedEmail email);
}
