package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.RevocationFence;
import io.saasforge.iam.domain.session.RevocationFenceTarget;
import java.util.UUID;

public interface RevocationFenceOperations extends UserTokenIssuanceFence {
    RevocationFence establish(UUID revocationRequestId, RevocationFenceTarget target);
}
