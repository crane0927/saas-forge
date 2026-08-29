package io.saasforge.starter.security;

import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.UserAccessTokenInvalidException;
import io.saasforge.sdk.auth.VerifiedServiceAccessTokenClaims;
import io.saasforge.sdk.auth.VerifiedUserAccessTokenClaims;
import java.util.List;

final class ReceiverTokenAuthenticators {

    private static final String BEARER_PREFIX = "Bearer ";
    private final UserTokenClaimsVerifier userSignatures;
    private final UserAccessTokenContextRevocationChecker userRevocations;
    private final ServiceTokenClaimsVerifier serviceSignatures;
    private final ServiceAccessTokenRevocationChecker serviceRevocations;

    ReceiverTokenAuthenticators(
            UserTokenClaimsVerifier userSignatures,
            UserAccessTokenContextRevocationChecker userRevocations,
            ServiceTokenClaimsVerifier serviceSignatures,
            ServiceAccessTokenRevocationChecker serviceRevocations) {
        this.userSignatures = userSignatures;
        this.userRevocations = userRevocations;
        this.serviceSignatures = serviceSignatures;
        this.serviceRevocations = serviceRevocations;
    }

    UserAuthenticationContext user(String authorization) {
        try {
            var claims = userSignatures.verify(authorization);
            if (claims == null) {
                throw new AccessTokenInvalidException(TokenKind.USER, authorization != null);
            }
            boolean revoked;
            try {
                revoked = userRevocations.isRevoked(
                        claims.jti(), claims.kid(), claims.membershipId(), claims.tenantId());
            } catch (RuntimeException exception) {
                throw new TokenRevocationStatusUnavailableException(TokenKind.USER, exception);
            }
            if (revoked) {
                throw new AccessTokenInvalidException(TokenKind.USER, true);
            }
            UserAuthenticationContext.ContextType contextType = claims.membershipId() == null
                    ? UserAuthenticationContext.ContextType.PLATFORM
                    : UserAuthenticationContext.ContextType.TENANT;
            return new UserAuthenticationContext(
                    claims.identityId(), contextType, claims.membershipId(), claims.tenantId());
        } catch (TokenRevocationStatusUnavailableException | AccessTokenInvalidException exception) {
            throw exception;
        } catch (UserAccessTokenInvalidException exception) {
            throw new AccessTokenInvalidException(TokenKind.USER, authorization != null, exception);
        }
    }

    ServiceAuthenticationContext service(String authorization, List<String> requiredScopes) {
        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()) {
            throw new AccessTokenInvalidException(TokenKind.SERVICE, authorization != null);
        }
        try {
            var claims = serviceSignatures.verify(authorization.substring(BEARER_PREFIX.length()));
            if (claims == null) {
                throw new AccessTokenInvalidException(TokenKind.SERVICE, true);
            }
            boolean revoked;
            try {
                revoked = serviceRevocations.isRevoked(claims.clientId(), claims.kid());
            } catch (RuntimeException exception) {
                throw new TokenRevocationStatusUnavailableException(TokenKind.SERVICE, exception);
            }
            if (revoked) {
                throw new AccessTokenInvalidException(TokenKind.SERVICE, true);
            }
            if (!claims.scopes().containsAll(requiredScopes)) {
                throw new ServiceAccessTokenScopeInsufficientException();
            }
            return new ServiceAuthenticationContext(claims.clientId(), claims.scopes());
        } catch (TokenRevocationStatusUnavailableException
                | AccessTokenInvalidException
                | ServiceAccessTokenScopeInsufficientException exception) {
            throw exception;
        } catch (ServiceAccessTokenInvalidException exception) {
            throw new AccessTokenInvalidException(TokenKind.SERVICE, true, exception);
        }
    }
}

@FunctionalInterface
interface UserTokenClaimsVerifier {
    VerifiedUserAccessTokenClaims verify(String authorization);
}

@FunctionalInterface
interface ServiceTokenClaimsVerifier {
    VerifiedServiceAccessTokenClaims verify(String token);
}

final class AccessTokenInvalidException extends RuntimeException {
    private final TokenKind tokenKind;
    private final boolean credentialsPresent;

    AccessTokenInvalidException(TokenKind tokenKind, boolean credentialsPresent) {
        this.tokenKind = tokenKind;
        this.credentialsPresent = credentialsPresent;
    }

    AccessTokenInvalidException(TokenKind tokenKind, boolean credentialsPresent, Throwable cause) {
        super(cause);
        this.tokenKind = tokenKind;
        this.credentialsPresent = credentialsPresent;
    }

    boolean credentialsPresent() {
        return credentialsPresent;
    }

    String detail() {
        return "The " + tokenKind.label() + " Access Token is missing or invalid.";
    }
}

final class ServiceAccessTokenScopeInsufficientException extends RuntimeException {
}

final class TokenRevocationStatusUnavailableException extends RuntimeException {
    private final TokenKind tokenKind;

    TokenRevocationStatusUnavailableException(TokenKind tokenKind, Throwable cause) {
        super(cause);
        this.tokenKind = tokenKind;
    }

    String detail() {
        return "The " + tokenKind.label() + " Token revocation status is unavailable.";
    }
}

enum TokenKind {
    USER("User"),
    SERVICE("Service");

    private final String label;

    TokenKind(String label) {
        this.label = label;
    }

    String label() {
        return label;
    }
}
