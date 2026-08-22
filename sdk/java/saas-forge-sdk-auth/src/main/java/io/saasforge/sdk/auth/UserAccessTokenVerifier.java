package io.saasforge.sdk.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 严格校验 Platform 形态 User Access Token，拒绝 Tenant、Role 或 Permission 声明。 */
public final class UserAccessTokenVerifier {
    private static final Set<String> PLATFORM_CLAIMS = Set.of(
            "iss", "aud", "iat", "exp", "identityId", "jti");

    private final ServiceJwtVerificationKeyResolver keys;
    private final UserAccessTokenRevocationChecker revocations;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration clockSkew;

    public UserAccessTokenVerifier(
            ServiceJwtVerificationKeyResolver keys,
            UserAccessTokenRevocationChecker revocations,
            Clock clock,
            String issuer,
            String audience,
            Duration clockSkew) {
        if (keys == null || revocations == null || clock == null
                || issuer == null || issuer.isBlank()
                || audience == null || audience.isBlank()
                || clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalArgumentException("User Access Token 校验配置不合法");
        }
        this.keys = keys;
        this.revocations = revocations;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkew = clockSkew;
    }

    public UserAccessTokenClaims verifyPlatformToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() == "Bearer ".length()) {
            throw new UserAccessTokenInvalidException();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorization.substring("Bearer ".length()));
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !JOSEObjectType.JWT.equals(jwt.getHeader().getType())
                    || jwt.getHeader().getKeyID() == null) {
                throw new UserAccessTokenInvalidException();
            }
            ServiceJwtVerificationKey key = keys.findByKid(jwt.getHeader().getKeyID())
                    .orElseThrow(UserAccessTokenInvalidException::new);
            RSAKey rsaKey = new RSAKey.Builder(new Base64URL(key.modulus()), new Base64URL(key.exponent()))
                    .keyID(key.kid())
                    .build();
            if (!jwt.verify(new RSASSAVerifier(rsaKey))) {
                throw new UserAccessTokenInvalidException();
            }
            var claims = jwt.getJWTClaimsSet();
            if (!claims.getClaims().keySet().equals(PLATFORM_CLAIMS)
                    || !issuer.equals(claims.getIssuer())
                    || !List.of(audience).equals(claims.getAudience())) {
                throw new UserAccessTokenInvalidException();
            }
            Instant issuedAt = claims.getIssueTime().toInstant();
            Instant expiresAt = claims.getExpirationTime().toInstant();
            Instant now = clock.instant();
            if (issuedAt.isAfter(now.plus(clockSkew))
                    || !expiresAt.plus(clockSkew).isAfter(now)
                    || !expiresAt.isAfter(issuedAt)) {
                throw new UserAccessTokenInvalidException();
            }
            UUID identityId = canonicalUuidV7(claims.getStringClaim("identityId"));
            UUID jti = canonicalUuidV7(claims.getJWTID());
            if (revocations.isRevoked(jti, key.kid())) {
                throw new UserAccessTokenInvalidException();
            }
            return new UserAccessTokenClaims(identityId, jti, key.kid(), issuedAt, expiresAt);
        } catch (UserAccessTokenInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new UserAccessTokenInvalidException(exception);
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new UserAccessTokenInvalidException();
        }
        return id;
    }
}
