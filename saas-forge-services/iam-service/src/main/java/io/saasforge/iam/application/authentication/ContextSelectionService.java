package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ContextSelectionService {
    private final AccessibleMemberships accessibleMemberships;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final LoginSessionService sessionService;
    private final Clock clock;

    public ContextSelectionService(
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            Clock clock) {
        this.accessibleMemberships = accessibleMemberships;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public AccessTokenLoginResult select(String refreshTokenValue, UUID membershipId) {
        Instant inspectedAt = clock.instant();
        RefreshTokenMaterial presentedToken = new RefreshTokenMaterial(
                refreshTokenValue, refreshTokenIssuer.digest(refreshTokenValue));
        RefreshTokenFamily selectionFamily = refreshTokenFamilies
                .findUsableSelectionByTokenDigest(presentedToken.digest(), inspectedAt)
                .orElseThrow(ContextSelectionSessionInvalidException::new);

        List<AccessibleMembership> memberships = accessibleMemberships.findByIdentityId(selectionFamily.identityId());
        if (memberships.size() > 100) {
            throw new AccessibleMembershipLimitExceededException();
        }
        AccessibleMembership selectedMembership = memberships.stream()
                .filter(membership -> membership.membershipId().equals(membershipId))
                .findFirst()
                .orElse(null);
        if (selectedMembership == null) {
            sessionService.rejectSelection(presentedToken, clock.instant());
            throw new ContextSelectionRejectedException();
        }

        // 签名失败必须发生在选择 Token 被消费之前，使客户端仍可安全重试。
        IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(
                selectionFamily.identityId(), selectedMembership.membershipId(), selectedMembership.tenantId());
        RefreshTokenMaterial nextToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.completeSelection(
                        presentedToken, nextToken, selectedMembership.membershipId(), selectedMembership.tenantId(),
                        accessToken, clock.instant())
                .orElseThrow(ContextSelectionSessionInvalidException::new);
        return new AccessTokenLoginResult(
                accessToken,
                nextToken.value(),
                cookieMaxAge,
                new TenantAuthenticationContextSnapshot(selectedMembership, memberships));
    }
}
