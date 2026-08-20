package io.saasforge.iam.application.authentication;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.identity.CredentialType;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import io.saasforge.iam.domain.identity.PasswordCredential;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

public final class PlatformLoginService {
    private final IdentityRepository identities;
    private final PlatformRoleAssignmentRepository platformRoles;
    private final LoginProtection loginProtection;
    private final PasswordVerifier passwordVerifier;
    private final UserAccessTokenIssuer accessTokenIssuer;
    private final RefreshTokenIssuer refreshTokenIssuer;
    private final PlatformLoginSessionService sessionService;
    private final Clock clock;

    public PlatformLoginService(
            IdentityRepository identities,
            PlatformRoleAssignmentRepository platformRoles,
            LoginProtection loginProtection,
            PasswordVerifier passwordVerifier,
            UserAccessTokenIssuer accessTokenIssuer,
            RefreshTokenIssuer refreshTokenIssuer,
            PlatformLoginSessionService sessionService,
            Clock clock) {
        this.identities = identities;
        this.platformRoles = platformRoles;
        this.loginProtection = loginProtection;
        this.passwordVerifier = passwordVerifier;
        this.accessTokenIssuer = accessTokenIssuer;
        this.refreshTokenIssuer = refreshTokenIssuer;
        this.sessionService = sessionService;
        this.clock = clock;
    }

    public PlatformLoginResult login(
            String email,
            String password,
            LoginContextType contextType,
            String traceId) {
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

        // 密码已经验证成功；后续访问上下文或签名失败不得继续累积锁定次数。
        loginProtection.clearCredentialFailures(normalizedEmail);
        Identity authenticatedIdentity = identity.orElseThrow();
        if (contextType != LoginContextType.PLATFORM
                || !platformRoles.hasActiveAssignment(authenticatedIdentity.id(), now)) {
            throw new AccessContextUnavailableException();
        }
        IssuedAccessToken accessToken = accessTokenIssuer.issuePlatformToken(authenticatedIdentity.id());
        RefreshTokenMaterial refreshToken = refreshTokenIssuer.issue();
        long cookieMaxAge = sessionService.start(authenticatedIdentity.id(), accessToken, refreshToken, traceId);
        return new PlatformLoginResult(accessToken, refreshToken.value(), cookieMaxAge);
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
