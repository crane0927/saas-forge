package io.saas.forge.iam.domain.session;

import java.util.UUID;

public record UserSessionFenceRelease(
        UUID releaseRequestId, UUID revocationRequestId, RevocationFenceTarget target) {}
