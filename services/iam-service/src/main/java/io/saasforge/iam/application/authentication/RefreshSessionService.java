package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.session.RefreshRotation;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RefreshSessionService {
    private final PlatformRoleAssignmentRepository platformRoles;
    private final AccessibleMemberships accessibleMemberships;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final LoginSessionService sessionService;
    private final RefreshRotationLease rotationLease;
    private final RefreshRotationTransaction rotationTransaction;
    private final Clock clock;

    public RefreshSessionService(
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            RefreshTokenFamilyRepository refreshTokenFamilies,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            RefreshRotationLease rotationLease,
            RefreshRotationTransaction rotationTransaction,
            Clock clock) {
        this.platformRoles = platformRoles;
        this.accessibleMemberships = accessibleMemberships;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.sessionService = sessionService;
        this.rotationLease = rotationLease;
        this.rotationTransaction = rotationTransaction;
        this.clock = clock;
    }

    public LoginResult refresh(UUID idempotencyKey, String refreshTokenValue, String traceId) {
        Instant inspectedAt = clock.instant();
        RefreshTokenMaterial presentedToken = new RefreshTokenMaterial(
                refreshTokenValue, refreshTokenIssuer.digest(refreshTokenValue));
        Sha256Digest idempotencyKeyDigest = digest(idempotencyKey.toString());
        RefreshRotationLease.Acquisition lease = rotationLease.acquire(
                presentedToken.digest(), idempotencyKeyDigest);
        if (!lease.acquired()) {
            throw new RefreshRotationInProgressException(lease.retryAfterSeconds());
        }
        RefreshTokenFamily family = refreshTokenFamilies
                .findByTokenDigest(presentedToken.digest())
                .orElseThrow(RefreshSessionInvalidException::new);
        if (!family.isUsableAt(inspectedAt)) {
            throw new RefreshSessionInvalidException();
        }
        return switch (family.purpose()) {
            case USER_PLATFORM -> refreshPlatform(
                    family, presentedToken, idempotencyKeyDigest, inspectedAt, traceId);
            case USER_TENANT -> refreshTenant(family, presentedToken, idempotencyKeyDigest, traceId);
            case USER_TENANT_SELECTION -> refreshSelection(
                    family, presentedToken, idempotencyKeyDigest, traceId);
            case INITIAL_PASSWORD_CHANGE -> throw new RefreshSessionInvalidException();
        };
    }

    private LoginResult refreshPlatform(
            RefreshTokenFamily family,
            RefreshTokenMaterial presentedToken,
            Sha256Digest idempotencyKeyDigest,
            Instant inspectedAt,
            String traceId) {
        if (!platformRoles.hasActiveAssignment(family.identityId(), inspectedAt)) {
            rejectAuthorization(presentedToken);
        }
        return rotateWithAccessToken(
                family, presentedToken, idempotencyKeyDigest, null, null, traceId);
    }

    private LoginResult refreshTenant(
            RefreshTokenFamily family,
            RefreshTokenMaterial presentedToken,
            Sha256Digest idempotencyKeyDigest,
            String traceId) {
        AccessibleMembership membership = accessibleMemberships.findByIdentityId(family.identityId()).stream()
                .filter(candidate -> candidate.membershipId().equals(family.membershipId()))
                .filter(candidate -> candidate.tenantId().equals(family.tenantId()))
                .findFirst()
                .orElse(null);
        if (membership == null) {
            rejectAuthorization(presentedToken);
        }
        return rotateWithAccessToken(
                family, presentedToken, idempotencyKeyDigest,
                membership.membershipId(), membership.tenantId(), traceId);
    }

    private LoginResult refreshSelection(
            RefreshTokenFamily family,
            RefreshTokenMaterial presentedToken,
            Sha256Digest idempotencyKeyDigest,
            String traceId) {
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
            long cookieMaxAge = commitRotation(
                    presentedToken, nextToken, idempotencyKeyDigest,
                    membership.membershipId(), membership.tenantId(), accessToken, traceId);
            return new AccessTokenLoginResult(accessToken, nextToken.value(), cookieMaxAge);
        }
        RefreshTokenMaterial nextToken = refreshTokenIssuer.issue();
        long cookieMaxAge = commitRotation(
                presentedToken, nextToken, idempotencyKeyDigest, null, null, null, traceId);
        return new ContextSelectionLoginResult(memberships, nextToken.value(), cookieMaxAge);
    }

    private LoginResult rotateWithAccessToken(
            RefreshTokenFamily family,
            RefreshTokenMaterial presentedToken,
            Sha256Digest idempotencyKeyDigest,
            java.util.UUID membershipId,
            java.util.UUID tenantId,
            String traceId) {
        // 签名失败必须发生在旧 Refresh Token 被消费之前，确保同一 Cookie 可以安全重试。
        IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(
                family.identityId(), membershipId, tenantId);
        RefreshTokenMaterial nextToken = refreshTokenIssuer.issue();
        long cookieMaxAge = commitRotation(
                presentedToken, nextToken, idempotencyKeyDigest,
                membershipId, tenantId, accessToken, traceId);
        return new AccessTokenLoginResult(accessToken, nextToken.value(), cookieMaxAge);
    }

    private long commitRotation(
            RefreshTokenMaterial presentedToken,
            RefreshTokenMaterial nextToken,
            Sha256Digest idempotencyKeyDigest,
            UUID membershipId,
            UUID tenantId,
            IssuedAccessToken accessToken,
            String traceId) {
        RefreshRotationTransaction.Result result = rotationTransaction.commit(
                presentedToken, nextToken, idempotencyKeyDigest, membershipId, tenantId,
                accessToken, clock.instant(), traceId);
        if (result.status() != RefreshRotation.Status.ROTATED
                && result.status() != RefreshRotation.Status.RECOVERED) {
            throw new RefreshSessionInvalidException();
        }
        return result.cookieMaxAgeSeconds().orElseThrow();
    }

    private void rejectAuthorization(RefreshTokenMaterial presentedToken) {
        sessionService.revokeForAuthorizationLoss(presentedToken, clock.instant());
        throw new RefreshAuthorizationRejectedException();
    }

    private static Sha256Digest digest(String value) {
        try {
            return Sha256Digest.of(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }
}
