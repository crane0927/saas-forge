package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public final class RefreshSessionService {
    private final PlatformRoleAssignmentRepository platformRoles;
    private final AccessibleMemberships accessibleMemberships;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final LoginSessionService sessionService;
    private final Clock clock;

    public RefreshSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            Clock clock) {
        this.platformRoles = platformRoles;
        this.accessibleMemberships = accessibleMemberships;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public LoginResult refresh(String refreshTokenValue) {
        Instant inspectedAt = clock.instant();
        RefreshTokenMaterial presentedToken = new RefreshTokenMaterial(
                refreshTokenValue, refreshTokenIssuer.digest(refreshTokenValue));
        RefreshTokenFamily family = refreshTokenFamilies
                .findUsableByTokenDigest(presentedToken.digest(), inspectedAt)
                .orElseThrow(RefreshSessionInvalidException::new);
        return switch (family.purpose()) {
            case USER_PLATFORM -> refreshPlatform(family, presentedToken, inspectedAt);
            case USER_TENANT -> refreshTenant(family, presentedToken);
            case USER_TENANT_SELECTION -> refreshSelection(family, presentedToken);
            case INITIAL_PASSWORD_CHANGE -> throw new RefreshSessionInvalidException();
        };
    }

    private LoginResult refreshPlatform(
            RefreshTokenFamily family, RefreshTokenMaterial presentedToken, Instant inspectedAt) {
        if (!platformRoles.hasActiveAssignment(family.identityId(), inspectedAt)) {
            rejectAuthorization(presentedToken);
        }
        return rotateWithAccessToken(family, presentedToken, null, null);
    }

    private LoginResult refreshTenant(RefreshTokenFamily family, RefreshTokenMaterial presentedToken) {
        AccessibleMembership membership = accessibleMemberships.findByIdentityId(family.identityId()).stream()
                .filter(candidate -> candidate.membershipId().equals(family.membershipId()))
                .filter(candidate -> candidate.tenantId().equals(family.tenantId()))
                .findFirst()
                .orElse(null);
        if (membership == null) {
            rejectAuthorization(presentedToken);
        }
        return rotateWithAccessToken(
                family, presentedToken, membership.membershipId(), membership.tenantId());
    }

    private LoginResult refreshSelection(RefreshTokenFamily family, RefreshTokenMaterial presentedToken) {
        List<AccessibleMembership> memberships = accessibleMemberships.findByIdentityId(family.identityId());
        if (memberships.isEmpty()) {
            rejectAuthorization(presentedToken);
        }
        if (memberships.size() > 100) {
            throw new AccessibleMembershipLimitExceededException();
        }
        if (memberships.size() == 1) {
            AccessibleMembership membership = memberships.get(0);
            IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(
                    family.identityId(), membership.membershipId(), membership.tenantId());
            RefreshTokenMaterial nextToken = refreshTokenIssuer.issue();
            long cookieMaxAge = sessionService.completeSelection(
                            presentedToken, nextToken, membership.membershipId(), membership.tenantId(),
                            accessToken, clock.instant())
                    .orElseThrow(RefreshSessionInvalidException::new);
            return new AccessTokenLoginResult(accessToken, nextToken.value(), cookieMaxAge);
        }
        RefreshTokenMaterial nextToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.rotateSelectionSession(presentedToken, nextToken, clock.instant())
                .orElseThrow(RefreshSessionInvalidException::new);
        return new ContextSelectionLoginResult(memberships, nextToken.value(), cookieMaxAge);
    }

    private LoginResult rotateWithAccessToken(
            RefreshTokenFamily family,
            RefreshTokenMaterial presentedToken,
            java.util.UUID membershipId,
            java.util.UUID tenantId) {
        // 签名失败必须发生在旧 Refresh Token 被消费之前，确保同一 Cookie 可以安全重试。
        IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(
                family.identityId(), membershipId, tenantId);
        RefreshTokenMaterial nextToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.rotateAccessTokenSession(
                        presentedToken, nextToken, membershipId, tenantId, accessToken, clock.instant())
                .orElseThrow(RefreshSessionInvalidException::new);
        return new AccessTokenLoginResult(accessToken, nextToken.value(), cookieMaxAge);
    }

    private void rejectAuthorization(RefreshTokenMaterial presentedToken) {
        sessionService.revokeForAuthorizationLoss(presentedToken, clock.instant());
        throw new RefreshAuthorizationRejectedException();
    }
}
