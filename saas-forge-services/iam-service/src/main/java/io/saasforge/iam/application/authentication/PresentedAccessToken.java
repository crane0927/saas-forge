package io.saasforge.iam.application.authentication;

import java.time.Instant;
import java.util.UUID;

public record PresentedAccessToken(UUID jti, String kid, Instant expiresAt) {
    public PresentedAccessToken {
        if (jti == null || jti.version() != 7 || kid == null || kid.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("Bearer Access Token 引用不合法");
        }
    }
}
