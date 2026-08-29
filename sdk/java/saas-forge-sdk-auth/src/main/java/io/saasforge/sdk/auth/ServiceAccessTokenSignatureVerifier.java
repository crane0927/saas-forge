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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/** 验证 IAM Service Access Token 的签名和固定 Claim 形态，不读取运行时撤销状态。 */
public final class ServiceAccessTokenSignatureVerifier {
    private static final JOSEObjectType ACCESS_TOKEN_TYPE = new JOSEObjectType("at+jwt");
    private static final Set<String> CLAIMS = Set.of(
            "iss", "aud", "iat", "exp", "jti", "sub", "client_id", "scope");

    private final ServiceJwtVerificationKeyResolver keys;
    private final Clock clock;
    private final String issuer;
    private final String audience;
    private final Duration clockSkew;

    public ServiceAccessTokenSignatureVerifier(
            ServiceJwtVerificationKeyResolver keys,
            Clock clock,
            String issuer,
            String audience,
            Duration clockSkew) {
        if (keys == null || clock == null || issuer == null || issuer.isBlank()
                || audience == null || audience.isBlank()
                || clockSkew == null || clockSkew.isNegative()) {
            throw new IllegalArgumentException("Service Access Token 验签配置不合法");
        }
        this.keys = keys;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
        this.clockSkew = clockSkew;
    }

    public VerifiedServiceAccessTokenClaims verify(
            String token, UUID expectedClientId, String requiredScope) {
        if (expectedClientId == null) {
            throw new ServiceAccessTokenInvalidException();
        }
        VerifiedServiceAccessTokenClaims claims = verify(token, requiredScope);
        if (!claims.clientId().equals(expectedClientId)) {
            throw new ServiceAccessTokenInvalidException();
        }
        return claims;
    }

    public VerifiedServiceAccessTokenClaims verify(String token, String requiredScope) {
        if (requiredScope == null || requiredScope.isBlank()) {
            throw new ServiceAccessTokenInvalidException();
        }
        VerifiedServiceAccessTokenClaims claims = verify(token);
        if (!claims.scopes().contains(requiredScope)) {
            throw new ServiceAccessTokenScopeException();
        }
        return claims;
    }

    /** 只验证固定 Service Token 形态；operation Scope 与撤销状态由组合授权边界继续判断。 */
    public VerifiedServiceAccessTokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new ServiceAccessTokenInvalidException();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            String kid = jwt.getHeader().getKeyID();
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !ACCESS_TOKEN_TYPE.equals(jwt.getHeader().getType())
                    || kid == null) {
                throw new ServiceAccessTokenInvalidException();
            }
            ServiceJwtVerificationKey key = keys.findByKid(kid)
                    .orElseThrow(ServiceAccessTokenInvalidException::new);
            RSAKey rsaKey = new RSAKey.Builder(new Base64URL(key.modulus()), new Base64URL(key.exponent()))
                    .keyID(key.kid())
                    .build();
            if (!jwt.verify(new RSASSAVerifier(rsaKey))) {
                throw new ServiceAccessTokenInvalidException();
            }
            var claims = jwt.getJWTClaimsSet();
            if (!claims.getClaims().keySet().equals(CLAIMS)
                    || !issuer.equals(claims.getIssuer())
                    || !List.of(audience).equals(claims.getAudience())) {
                throw new ServiceAccessTokenInvalidException();
            }
            Instant issuedAt = claims.getIssueTime().toInstant();
            Instant expiresAt = claims.getExpirationTime().toInstant();
            Instant now = clock.instant();
            if (issuedAt.isAfter(now.plus(clockSkew))
                    || !expiresAt.plus(clockSkew).isAfter(now)
                    || !expiresAt.isAfter(issuedAt)) {
                throw new ServiceAccessTokenInvalidException();
            }
            UUID clientId = canonicalUuidV7(claims.getStringClaim("client_id"));
            if (!clientId.toString().equals(claims.getSubject())) {
                throw new ServiceAccessTokenInvalidException();
            }
            UUID jti = canonicalUuidV7(claims.getJWTID());
            Set<String> scopes = scopes(claims.getStringClaim("scope"));
            return new VerifiedServiceAccessTokenClaims(clientId, scopes, jti, kid, issuedAt, expiresAt);
        } catch (ServiceAccessTokenInvalidException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ServiceAccessTokenInvalidException(exception);
        }
    }

    private static UUID canonicalUuidV7(String value) {
        UUID id = UUID.fromString(value);
        if (id.version() != 7 || !id.toString().equals(value)) {
            throw new ServiceAccessTokenInvalidException();
        }
        return id;
    }

    private static Set<String> scopes(String value) {
        if (value == null || value.isBlank()) {
            throw new ServiceAccessTokenInvalidException();
        }
        List<String> values = Arrays.asList(value.split(" ", -1));
        LinkedHashSet<String> distinct = new LinkedHashSet<>(values);
        String canonical = distinct.stream()
                .collect(Collectors.toCollection(TreeSet::new))
                .stream()
                .collect(Collectors.joining(" "));
        if (distinct.size() != values.size() || !canonical.equals(value)) {
            throw new ServiceAccessTokenInvalidException();
        }
        return distinct;
    }
}
