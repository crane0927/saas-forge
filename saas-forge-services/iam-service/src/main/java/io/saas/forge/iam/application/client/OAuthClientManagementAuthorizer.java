package io.saas.forge.iam.application.client;

import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saas.forge.sdk.auth.UserAccessTokenInvalidException;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.VerifiedUserAccessTokenClaims;
import java.time.Clock;
import java.util.UUID;

/** IAM 在管理用例前独立校验原始 Bearer Token、撤销状态和当前平台角色。 */
public final class OAuthClientManagementAuthorizer {
    private static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    private final UserAccessTokenSignatureVerifier signatures;
    private final RevocationIndex revocations;
    private final PlatformRoleAssignmentRepository roles;
    private final Clock clock;

    public OAuthClientManagementAuthorizer(
            UserAccessTokenSignatureVerifier signatures,
            RevocationIndex revocations,
            PlatformRoleAssignmentRepository roles,
            Clock clock) {
        this.signatures = signatures;
        this.revocations = revocations;
        this.roles = roles;
        this.clock = clock;
    }

    public UUID authorize(String authorization) {
        final VerifiedUserAccessTokenClaims claims;
        try {
            claims = signatures.verify(authorization);
        } catch (UserAccessTokenInvalidException exception) {
            throw OAuthClientManagementAuthorizationException.accessTokenInvalid(exception);
        }
        if (claims.membershipId() != null || claims.tenantId() != null) {
            throw OAuthClientManagementAuthorizationException.platformContextRequired();
        }
        if (revocations.isTokenRevoked(claims.jti(), claims.kid())) {
            throw OAuthClientManagementAuthorizationException.accessTokenInvalid(null);
        }
        if (!roles.hasActiveAssignment(claims.identityId(), PLATFORM_ADMIN, clock.instant())) {
            throw OAuthClientManagementAuthorizationException.platformAdminRequired();
        }
        return claims.identityId();
    }
}
