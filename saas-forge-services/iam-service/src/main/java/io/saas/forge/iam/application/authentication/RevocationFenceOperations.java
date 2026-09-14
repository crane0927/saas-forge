package io.saas.forge.iam.application.authentication;

import io.saas.forge.iam.domain.session.RevocationFence;
import io.saas.forge.iam.domain.session.RevocationFenceTarget;
import java.util.UUID;

public interface RevocationFenceOperations extends UserTokenIssuanceFence {
    RevocationFence establish(UUID revocationRequestId, RevocationFenceTarget target);
}
