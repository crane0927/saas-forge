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
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GatewayUserAccessTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-26T05:00:00Z");
    private static final String ISSUER = "https://iam.test.saasforge.invalid";
    private static final UUID IDENTITY_ID = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abc");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abd");
    private static final UUID TENANT_ID = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abe");
    private static final UUID JTI = UUID.fromString("018f2d3a-4b5c-7d6e-8f90-123456789abf");

    private RSAKey key;
    private GatewayUserTokenRevocationChecker revocations;

    @BeforeEach
    void setUp() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("gateway-test-kid").generate();
        revocations = (jti, kid, membershipId, tenantId) -> {
        };
    }

    @Test
    void acceptsPlatformAndTenantTokensBeforeCheckingTheirApplicableFenceScope() throws Exception {
        GatewayUserAccessTokenVerifier verifier = verifier();

        assertDoesNotThrow(() -> verifier.verify("Bearer " + token(Map.of())));
        assertDoesNotThrow(() -> verifier.verify("Bearer " + token(Map.of(
                "membershipId", MEMBERSHIP_ID.toString(), "tenantId", TENANT_ID.toString()))));
    }

    @Test
    void rejectsMissingMalformedExpiredAndUnknownKidTokens() throws Exception {
        GatewayUserAccessTokenVerifier verifier = verifier();

        assertThrows(GatewayUserTokenInvalidException.class, () -> verifier.verify(null));
        assertThrows(GatewayUserTokenInvalidException.class, () -> verifier.verify("Bearer malformed"));
        assertThrows(GatewayUserTokenInvalidException.class,
                () -> verifier.verify("Bearer " + token(Map.of(), NOW.minusSeconds(120), NOW.minusSeconds(60), key)));
        RSAKey otherKey = new RSAKeyGenerator(2048).keyID("unknown-kid").generate();
        assertThrows(GatewayUserTokenInvalidException.class,
                () -> verifier.verify("Bearer " + token(Map.of(), NOW.minusSeconds(10), NOW.plusSeconds(60), otherKey)));
    }

    @Test
    void rejectsJtiOrTenantMembershipFenceHits() throws Exception {
        GatewayUserAccessTokenVerifier jtiRevoked = verifier((jti, kid, membershipId, tenantId) -> {
            throw new GatewayUserTokenInvalidException();
        });
        GatewayUserAccessTokenVerifier fenced = verifier((jti, kid, membershipId, tenantId) -> {
            if (membershipId != null && tenantId != null) {
                throw new GatewayUserTokenInvalidException();
            }
        });

        assertThrows(GatewayUserTokenInvalidException.class, () -> jtiRevoked.verify("Bearer " + token(Map.of())));
        assertThrows(GatewayUserTokenInvalidException.class, () -> fenced.verify("Bearer " + token(Map.of(
                "membershipId", MEMBERSHIP_ID.toString(), "tenantId", TENANT_ID.toString()))));
    }

    @Test
    void propagatesUnavailableRevocationStatusWithoutDowngradingItToInvalidToken() throws Exception {
        GatewayUserAccessTokenVerifier verifier = verifier((jti, kid, membershipId, tenantId) -> {
            throw new GatewayTokenRevocationStatusUnavailableException();
        });

        assertThrows(GatewayTokenRevocationStatusUnavailableException.class,
                () -> verifier.verify("Bearer " + token(Map.of())));
    }

    private GatewayUserAccessTokenVerifier verifier() {
        return verifier(revocations);
    }

    private GatewayUserAccessTokenVerifier verifier(GatewayUserTokenRevocationChecker checker) {
        return new GatewayUserAccessTokenVerifier(
                new UserAccessTokenSignatureVerifier(
                        kid -> "gateway-test-kid".equals(kid)
                                ? Optional.of(new ServiceJwtVerificationKey(
                                        key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()))
                                : Optional.empty(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        ISSUER,
                        "saasforge-api",
                        Duration.ofSeconds(30)),
                checker);
    }

    private String token(Map<String, String> context) throws Exception {
        return token(context, NOW.minusSeconds(10), NOW.plusSeconds(60), key);
    }

    private String token(Map<String, String> context, Instant issuedAt, Instant expiresAt, RSAKey signingKey) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience("saasforge-api")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .claim("identityId", IDENTITY_ID.toString())
                .jwtID(JTI.toString());
        context.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.getKeyID())
                .build(), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }
}
