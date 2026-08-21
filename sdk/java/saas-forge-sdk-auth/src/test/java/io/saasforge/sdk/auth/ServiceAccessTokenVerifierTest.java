package io.saasforge.sdk.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServiceAccessTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
    private static final String ISSUER = "https://iam.test";
    private static final String AUDIENCE = "saasforge-api";
    private static final UUID CLIENT_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static RSAKey key;

    @BeforeAll
    static void generateKey() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("service-key").generate();
    }

    @Test
    void verifiesExactClientScopeAndClaims() throws Exception {
        ServiceAccessTokenClaims claims = verifier(NOW).verify(
                token(NOW.minusSeconds(1), NOW.plusSeconds(299), Map.of()),
                CLIENT_ID,
                "iam:platform-role:read");

        assertEquals(CLIENT_ID, claims.clientId());
        assertEquals(JTI, claims.jti());
        assertEquals(java.util.Set.of("iam:identity:write", "iam:platform-role:read"), claims.scopes());
    }

    @Test
    void rejectsExpiredTokenAfterClockSkew() throws Exception {
        String token = token(NOW.minusSeconds(400), NOW.minusSeconds(31), Map.of());

        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> verifier(NOW).verify(token, CLIENT_ID, "iam:identity:write"));
    }

    @Test
    void rejectsWrongClientScopeAndUserClaims() throws Exception {
        String valid = token(NOW, NOW.plusSeconds(300), Map.of());
        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> verifier(NOW).verify(valid, uuidV7("0198c9d5-0f25-7b21-8d67-31c8652d4c91"),
                        "iam:identity:write"));
        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> verifier(NOW).verify(valid, CLIENT_ID, "entitlement:quota:write"));

        String withUserClaim = token(NOW, NOW.plusSeconds(300), Map.of("identityId", CLIENT_ID.toString()));
        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> verifier(NOW).verify(withUserClaim, CLIENT_ID, "iam:identity:write"));
    }

    private static ServiceAccessTokenVerifier verifier(Instant now) {
        ServiceJwtVerificationKey publicKey = new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString());
        return new ServiceAccessTokenVerifier(
                kid -> key.getKeyID().equals(kid) ? Optional.of(publicKey) : Optional.empty(),
                Clock.fixed(now, ZoneOffset.UTC), ISSUER, AUDIENCE, Duration.ofSeconds(30));
    }

    private static String token(Instant issuedAt, Instant expiresAt, Map<String, Object> extraClaims) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .jwtID(JTI.toString())
                .subject(CLIENT_ID.toString())
                .claim("client_id", CLIENT_ID.toString())
                .claim("scope", "iam:identity:write iam:platform-role:read");
        extraClaims.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID(key.getKeyID())
                        .build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static UUID uuidV7(String value) {
        return UUID.fromString(value);
    }
}
