package io.saasforge.gateway.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayServiceAccessTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-29T01:00:00Z");
    private static final String ISSUER = "https://iam.test.saasforge.invalid";
    private static final UUID CLIENT_ID = UUID.fromString("0198f98d-83c2-75d0-82bd-ebdee5d0c613");
    private static final UUID JTI = UUID.fromString("0198f98d-83c2-75d0-82bd-ebdee5d0c614");
    private static final List<String> REQUIRED = List.of("runtime:quota:write", "runtime:read");

    private RSAKey key;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("gateway-service-test-kid").generate();
    }

    @Test
    void acceptsEveryRequiredScopeAndIgnoresAdditionalGrantedScope() throws Exception {
        GatewayServiceAccessTokenVerifier verifier = verifier((clientId, kid) -> {
        });

        assertDoesNotThrow(() -> verifier.verify("Bearer " + token(
                "runtime:quota:write runtime:read tenant-access:tenant:read", Map.of(), key), REQUIRED));
    }

    @Test
    void rejectsMissingBearerMalformedUserTypeBadSignatureAndUnexpectedClaims() throws Exception {
        GatewayServiceAccessTokenVerifier verifier = verifier((clientId, kid) -> {
        });
        RSAKey unknown = new RSAKeyGenerator(2048).keyID("unknown-kid").generate();

        assertThrows(GatewayServiceTokenInvalidException.class, () -> verifier.verify(null, REQUIRED));
        assertThrows(GatewayServiceTokenInvalidException.class, () -> verifier.verify("Bearer malformed", REQUIRED));
        assertThrows(GatewayServiceTokenInvalidException.class, () -> verifier.verify(
                "Bearer " + token("runtime:quota:write runtime:read", Map.of(), key, JOSEObjectType.JWT), REQUIRED));
        assertThrows(GatewayServiceTokenInvalidException.class, () -> verifier.verify(
                "Bearer " + token("runtime:quota:write runtime:read", Map.of(), unknown), REQUIRED));
        assertThrows(GatewayServiceTokenInvalidException.class, () -> verifier.verify(
                "Bearer " + token("runtime:quota:write runtime:read", Map.of("identityId", CLIENT_ID.toString()), key),
                REQUIRED));
    }

    @Test
    void checksRevocationBeforeReportingMissingScope() throws Exception {
        String missingScope = "Bearer " + token("runtime:read", Map.of(), key);

        GatewayServiceAccessTokenVerifier revoked = verifier((clientId, kid) -> {
            throw new GatewayServiceTokenInvalidException();
        });
        GatewayServiceAccessTokenVerifier unavailable = verifier((clientId, kid) -> {
            throw new GatewayTokenRevocationStatusUnavailableException();
        });

        assertThrows(GatewayServiceTokenInvalidException.class, () -> revoked.verify(missingScope, REQUIRED));
        assertThrows(GatewayTokenRevocationStatusUnavailableException.class,
                () -> unavailable.verify(missingScope, REQUIRED));
        assertThrows(GatewayServiceTokenScopeInsufficientException.class,
                () -> verifier((clientId, kid) -> {
                }).verify(missingScope, REQUIRED));
    }

    private GatewayServiceAccessTokenVerifier verifier(GatewayServiceTokenRevocationChecker revocations) {
        return new GatewayServiceAccessTokenVerifier(
                new ServiceAccessTokenSignatureVerifier(
                        kid -> key.getKeyID().equals(kid)
                                ? Optional.of(new ServiceJwtVerificationKey(
                                        key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()))
                                : Optional.empty(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        ISSUER,
                        "saasforge-api",
                        Duration.ofSeconds(30)),
                revocations);
    }

    private String token(String scopes, Map<String, Object> extraClaims, RSAKey signingKey) throws Exception {
        return token(scopes, extraClaims, signingKey, new JOSEObjectType("at+jwt"));
    }

    private String token(
            String scopes,
            Map<String, Object> extraClaims,
            RSAKey signingKey,
            JOSEObjectType type) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("saasforge-api")
                .issueTime(Date.from(NOW.minusSeconds(10)))
                .expirationTime(Date.from(NOW.plusSeconds(300)))
                .jwtID(JTI.toString())
                .subject(CLIENT_ID.toString())
                .claim("client_id", CLIENT_ID.toString())
                .claim("scope", scopes);
        extraClaims.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(type)
                .keyID(signingKey.getKeyID())
                .build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
