package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.identity.NormalizedEmail;

public interface LoginProtection {
    boolean isLocked(NormalizedEmail email);

    void recordCredentialFailure(NormalizedEmail email);

    void clearCredentialFailures(NormalizedEmail email);
}
