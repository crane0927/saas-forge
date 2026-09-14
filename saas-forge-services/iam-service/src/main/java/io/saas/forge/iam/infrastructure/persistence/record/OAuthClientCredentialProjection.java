package io.saas.forge.iam.infrastructure.persistence.record;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OAuthClientCredentialProjection(UUID clientId, OffsetDateTime overlapEndsAt,
        boolean canRotate, boolean canRevoke) { }
