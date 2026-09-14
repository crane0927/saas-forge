package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.session.RefreshTokenFamilyPurpose;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class PasswordLoginService {
    private static final int ACCESSIBLE_MEMBERSHIP_LIMIT = 100;
    private static final String PLATFORM_ADMIN_ROLE = "PLATFORM_ADMIN";

    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository platformRoles;
    private final AccessibleMemberships accessibleMemberships;
    private final LoginProtection loginProtection;
    private final PasswordVerifier passwordVerifier;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final RefreshTokenFamilyRepository refreshTokenFamilies;
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
            RefreshTokenFamilyRepository refreshTokenFamilies,
            LoginSessionService sessionService,
            Clock clock) {
        this.identities = identities;
        this.platformRoles = platformRoles;
        this.accessibleMemberships = accessibleMemberships;
        this.loginProtection = loginProtection;
        this.passwordVerifier = passwordVerifier;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.refreshTokenFamilies = refreshTokenFamilies;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public LoginResult login(
            String email,
            String password,
            LoginContextType contextType,
            String selectedRefreshToken,
            String traceId) {
        requireAvailableSlot(BrowserSessionSlot.forLogin(contextType), selectedRefreshToken);
        NormalizedEmail normalizedEmail = NormalizedEmail.from(email);
        if (loginProtection.isLocked(normalizedEmail)) {
            throw new AuthenticationFailedException();
        }
        Instant now = clock.instant();
        Optional<Identity> identity = identities.findByEmail(normalizedEmail);
        List<PasswordCredential> credentials = identity.map(value -> activePasswords(value, now)).orElseGet(List::of);
        if (credentials.isEmpty()) {
            passwordVerifier.dummyMatches(password);
            failCredential(normalizedEmail);
        }
        Optional<PasswordCredential> credential = credentials.stream()
                .filter(candidate -> passwordVerifier.matches(password, candidate.passwordHash()))
                .findFirst();
        if (credential.isEmpty()) {
            failCredential(normalizedEmail);
        }

        // 密码已经验证成功；后续访问上下文或基础设施失败不得继续累积锁定次数。
        loginProtection.clearCredentialFailures(normalizedEmail);
        Identity authenticatedIdentity = identity.orElseThrow();
        PasswordCredential authenticatedCredential = credential.orElseThrow();
        if (authenticatedCredential.type() == CredentialType.INITIAL_PLATFORM_PASSWORD) {
            if (contextType != LoginContextType.PLATFORM) {
                throw new BrowserRequestRejectedException();
            }
            return initialPasswordChangeLogin(authenticatedIdentity, authenticatedCredential, now, traceId);
        }
        return switch (contextType) {
            case PLATFORM -> platformLogin(authenticatedIdentity, now, traceId);
            case TENANT -> tenantLogin(authenticatedIdentity, traceId);
        };
    }

    private void requireAvailableSlot(BrowserSessionSlot slot, String selectedRefreshToken) {
        if (selectedRefreshToken == null) {
            return;
        }
        io.saasforge.iam.domain.shared.Sha256Digest digest;
        try {
            digest = refreshTokenIssuer.digest(selectedRefreshToken);
        } catch (ContextSelectionSessionInvalidException invalidRefreshToken) {
            // 无效或无法解析的旧槽位 Cookie 不阻止重新登录；成功响应会覆盖它。
            return;
        }
        refreshTokenFamilies.findByTokenDigest(digest).ifPresent(family -> {
            if (!slot.accepts(family.purpose())) {
                throw new BrowserRequestRejectedException();
            }
            if (family.isUsableAt(clock.instant())) {
                throw new SessionSlotAlreadyActiveException();
            }
        });
    }

    private LoginResult initialPasswordChangeLogin(
            Identity identity, PasswordCredential credential, Instant now, String traceId) {
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.startInitialPasswordChangeSession(
                identity.id(), credential.id(), credential.expiresAt(), refreshToken, now, traceId);
        return new InitialPasswordChangeLoginResult(refreshToken.value(), cookieMaxAge);
    }

    private LoginResult platformLogin(Identity identity, Instant now, String traceId) {
        if (!platformRoles.hasActiveAssignment(identity.id(), PLATFORM_ADMIN_ROLE, now)) {
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
            return new AccessTokenLoginResult(
                    accessToken,
                    refreshToken.value(),
                    cookieMaxAge,
                    new TenantAuthenticationContextSnapshot(membership, memberships));
        }
        long cookieMaxAge = sessionService.startSelectionSession(identity.id(), refreshToken, clock.instant(), traceId);
        return new ContextSelectionLoginResult(memberships, refreshToken.value(), cookieMaxAge);
    }

    private List<PasswordCredential> activePasswords(Identity identity, Instant now) {
        return identities.findCredentials(identity.id()).stream()
                .filter(credential -> credential.isValidAt(now))
                .sorted((left, right) -> Integer.compare(priority(left.type()), priority(right.type())))
                .toList();
    }

    private static int priority(CredentialType type) {
        return type == CredentialType.INITIAL_PLATFORM_PASSWORD ? 0 : 1;
    }

    private void failCredential(NormalizedEmail email) {
        loginProtection.recordCredentialFailure(email);
        throw new AuthenticationFailedException();
    }
}
