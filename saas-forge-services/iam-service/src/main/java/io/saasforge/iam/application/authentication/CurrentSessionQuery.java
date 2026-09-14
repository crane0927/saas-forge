package io.saasforge.iam.application.authentication;

import io.saasforge.iam.application.authorization.PlatformRoleAuthorizationService;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.sdk.auth.UserAccessTokenInvalidException;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.util.UUID;

/** 当前请求的 IAM 权威快照，不查询 Refresh Family，也不替代业务 operation 授权。 */
public final class CurrentSessionQuery {
    private final UserAccessTokenSignatureVerifier signatures;
    private final RevocationIndex revocations;
    private final IdentityRepository identities;
    private final PlatformRoleAuthorizationService roles;

    public CurrentSessionQuery(UserAccessTokenSignatureVerifier signatures, RevocationIndex revocations,
            IdentityRepository identities, PlatformRoleAuthorizationService roles) {
        this.signatures = signatures;
        this.revocations = revocations;
        this.identities = identities;
        this.roles = roles;
    }

    public Snapshot read(String authorization) {
        var claims = signatures.verify(authorization);
        try {
            if (revocations.isTokenRevoked(claims.jti(), claims.kid())) {
                throw new UserAccessTokenInvalidException();
            }
        } catch (RevocationIndexUnavailableException unavailable) {
            throw new TokenRevocationStatusUnavailableException();
        }
        if (claims.membershipId() != null || claims.tenantId() != null) {
            throw new AccessContextUnavailableException();
        }
        var identity = identities.findById(claims.identityId()).orElseThrow(UserAccessTokenInvalidException::new);
        return new Snapshot(identity.id(), identity.email().value(), identity.displayName(),
                roles.isAllowed(identity.id(), "PLATFORM_ADMIN"));
    }

    public record Snapshot(UUID identityId, String email, String displayName, boolean platformAdmin) {}
}
