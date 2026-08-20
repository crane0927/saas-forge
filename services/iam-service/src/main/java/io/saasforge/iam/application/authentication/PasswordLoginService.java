package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class PasswordLoginService {
    private static final int ACCESSIBLE_MEMBERSHIP_LIMIT = 100;

    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository platformRoles;
    private final AccessibleMemberships accessibleMemberships;
    private final LoginProtection loginProtection;
    private final PasswordVerifier passwordVerifier;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final LoginSessionService sessionService;
    private final Clock clock;

    public PasswordLoginService(
            IdentityRepository identities,
            PlatformRoleAssignmentRepository platformRoles,
            AccessibleMemberships accessibleMemberships,
            LoginProtection loginProtection,
            PasswordVerifier passwordVerifier,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            LoginSessionService sessionService,
            Clock clock) {
        this.identities = identities;
        this.platformRoles = platformRoles;
        this.accessibleMemberships = accessibleMemberships;
        this.loginProtection = loginProtection;
        this.passwordVerifier = passwordVerifier;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public LoginResult login(String email, String password, LoginContextType contextType, String traceId) {
        NormalizedEmail normalizedEmail = NormalizedEmail.from(email);
        if (loginProtection.isLocked(normalizedEmail)) {
            throw new AuthenticationFailedException();
        }
        Instant now = clock.instant();
        Optional<Identity> identity = identities.findByEmail(normalizedEmail);
        Optional<PasswordCredential> credential = identity.flatMap(value -> activePassword(value, now));
        if (credential.isEmpty()) {
            passwordVerifier.dummyMatches(password);
            failCredential(normalizedEmail);
        }
        if (!passwordVerifier.matches(password, credential.orElseThrow().passwordHash())) {
            failCredential(normalizedEmail);
        }

        // 密码已经验证成功；后续访问上下文或基础设施失败不得继续累积锁定次数。
        loginProtection.clearCredentialFailures(normalizedEmail);
        Identity authenticatedIdentity = identity.orElseThrow();
        return switch (contextType) {
            case PLATFORM -> platformLogin(authenticatedIdentity, now, traceId);
            case TENANT -> tenantLogin(authenticatedIdentity, traceId);
        };
    }

    private LoginResult platformLogin(Identity identity, Instant now, String traceId) {
        if (!platformRoles.hasActiveAssignment(identity.id(), now)) {
            throw new AccessContextUnavailableException();
        }
        IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(identity.id(), null, null);
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.startAccessTokenSession(
                identity.id(), RefreshTokenFamilyPurpose.USER_PLATFORM, null, null,
                accessToken, refreshToken, traceId);
        return new AccessTokenLoginResult(accessToken, refreshToken.value(), cookieMaxAge);
    }

    private LoginResult tenantLogin(Identity identity, String traceId) {
        List<AccessibleMembership> memberships = accessibleMemberships.findByIdentityId(identity.id());
        if (memberships.isEmpty()) {
            throw new AccessContextUnavailableException();
        }
        if (memberships.size() > ACCESSIBLE_MEMBERSHIP_LIMIT) {
            throw new AccessibleMembershipLimitExceededException();
        }
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        if (memberships.size() == 1) {
            AccessibleMembership membership = memberships.get(0);
            IssuedAccessToken accessToken = accessTokenIssuer.issueUserToken(
                    identity.id(), membership.membershipId(), membership.tenantId());
            long cookieMaxAge = sessionService.startAccessTokenSession(
                    identity.id(), RefreshTokenFamilyPurpose.USER_TENANT,
                    membership.membershipId(), membership.tenantId(), accessToken, refreshToken, traceId);
            return new AccessTokenLoginResult(accessToken, refreshToken.value(), cookieMaxAge);
        }
        long cookieMaxAge = sessionService.startSelectionSession(identity.id(), refreshToken, clock.instant(), traceId);
        return new ContextSelectionLoginResult(memberships, refreshToken.value(), cookieMaxAge);
    }

    private Optional<PasswordCredential> activePassword(Identity identity, Instant now) {
        return identities.findCredentials(identity.id()).stream()
                .filter(credential -> credential.type() == CredentialType.PASSWORD && credential.isValidAt(now))
                .findFirst();
    }

    private void failCredential(NormalizedEmail email) {
        loginProtection.recordCredentialFailure(email);
        throw new AuthenticationFailedException();
    }
}
