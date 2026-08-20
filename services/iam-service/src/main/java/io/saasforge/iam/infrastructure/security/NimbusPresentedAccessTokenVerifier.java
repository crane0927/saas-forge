package io.saasforge.iam.infrastructure.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.application.authentication.PresentedAccessToken;
import io.saasforge.iam.application.authentication.PresentedAccessTokenVerifier;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;

public final class NimbusPresentedAccessTokenVerifier implements PresentedAccessTokenVerifier {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final Set<String> PLATFORM_CLAIMS = Set.of("iss", "aud", "iat", "exp", "identityId", "jti");
    private static final Set<String> TENANT_CLAIMS = Set.of(
            "iss", "aud", "iat", "exp", "identityId", "membershipId", "tenantId", "jti");

    private final SigningKeyRepository signingKeys;
    private final Clock clock;
    private final String issuer;

    public NimbusPresentedAccessTokenVerifier(SigningKeyRepository signingKeys, Clock clock, String issuer) {
        this.signingKeys = signingKeys;
        this.clock = clock;
        this.issuer = issuer;
    }

    @Override
    public Optional<PresentedAccessToken> verify(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        try {
            SignedJWT jwt = SignedJWT.parse(authorizationHeader.substring(7));
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !JOSEObjectType.JWT.equals(jwt.getHeader().getType())
                    || jwt.getHeader().getKeyID() == null) {
                return Optional.empty();
            }
            SigningKey key = signingKeys.findPublishedVerificationKeys().stream()
                    .filter(candidate -> candidate.kid().equals(jwt.getHeader().getKeyID()))
                    .findFirst()
                    .orElse(null);
            if (key == null || !jwt.verify(new com.nimbusds.jose.crypto.RSASSAVerifier(publicKey(key)))) {
                return Optional.empty();
            }
            var claims = jwt.getJWTClaimsSet();
            Set<String> names = claims.getClaims().keySet();
            if (!names.equals(PLATFORM_CLAIMS) && !names.equals(TENANT_CLAIMS)) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            Instant issuedAt = claims.getIssueTime().toInstant();
            Instant expiresAt = claims.getExpirationTime().toInstant();
            if (!issuer.equals(claims.getIssuer())
                    || !List.of("saasforge-api").equals(claims.getAudience())
                    || issuedAt.isAfter(now.plus(CLOCK_SKEW))
                    || !expiresAt.plus(CLOCK_SKEW).isAfter(now)) {
                return Optional.empty();
            }
            UUID jti = UUID.fromString(claims.getJWTID());
            return Optional.of(new PresentedAccessToken(jti, key.kid(), expiresAt));
        } catch (DataAccessException unavailableSigningKeys) {
            throw unavailableSigningKeys;
        } catch (Exception invalidToken) {
            return Optional.empty();
        }
    }

    private static RSAKey publicKey(SigningKey key) {
        return new RSAKey.Builder(new Base64URL(key.publicJwkModulus()), new Base64URL(key.publicJwkExponent()))
                .keyID(key.kid())
                .build();
    }
}
