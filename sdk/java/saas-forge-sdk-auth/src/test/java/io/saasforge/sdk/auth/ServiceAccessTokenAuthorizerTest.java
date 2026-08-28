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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ServiceAccessTokenAuthorizerTest {
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
    private static final UUID CLIENT_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static RSAKey key;

    @BeforeAll
    static void generateKey() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("service-key").generate();
    }

    @Test
    void authorizesAndChecksOnlyCanonicalClientIdAndSigningKid() throws Exception {
        AtomicReference<UUID> checkedClientId = new AtomicReference<>();
        AtomicReference<String> checkedKid = new AtomicReference<>();
        ServiceAccessTokenAuthorizer authorizer = authorizer((clientId, kid) -> {
            checkedClientId.set(clientId);
            checkedKid.set(kid);
            return false;
        });

        ServiceAccessTokenClaims claims = authorizer.authorize(
                token("iam:identity:write iam:platform-role:read"),
                CLIENT_ID,
                "iam:platform-role:read");

        assertEquals(CLIENT_ID, claims.clientId());
        assertEquals(JTI, claims.jti());
        assertEquals(CLIENT_ID, checkedClientId.get());
        assertEquals("service-key", checkedKid.get());
    }

    @Test
    void rejectsInsufficientScopeBeforeReadingRevocationState() throws Exception {
        ServiceAccessTokenAuthorizer authorizer = authorizer((clientId, kid) -> {
            throw new AssertionError("Scope 不足时不应读取撤销状态");
        });

        assertThrows(ServiceAccessTokenScopeException.class,
                () -> authorizer.authorize(token("runtime:read"), "runtime:quota:write"));
    }

    @Test
    void rejectsRevokedSigningKid() throws Exception {
        ServiceAccessTokenAuthorizer authorizer = authorizer(
                (clientId, kid) -> "service-key".equals(kid));

        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> authorizer.authorize(token("runtime:read"), "runtime:read"));
    }

    @Test
    void rejectsRevokedClientId() throws Exception {
        ServiceAccessTokenAuthorizer authorizer = authorizer(
                (clientId, kid) -> CLIENT_ID.equals(clientId));

        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> authorizer.authorize(token("runtime:read"), "runtime:read"));
    }

    @Test
    void failsClosedWhenRevocationStateIsUnavailable() throws Exception {
        ServiceAccessTokenAuthorizer authorizer = authorizer((clientId, kid) -> {
            throw new IllegalStateException("revocation index unavailable");
        });

        assertThrows(ServiceAccessTokenInvalidException.class,
                () -> authorizer.authorize(token("runtime:read"), "runtime:read"));
    }

    @Test
    void signatureVerifierDoesNotRequireRuntimeRevocationState() throws Exception {
        VerifiedServiceAccessTokenClaims claims = signatureVerifier()
                .verify(token("runtime:read"), "runtime:read");

        assertEquals(CLIENT_ID, claims.clientId());
        assertEquals("service-key", claims.kid());
    }

    @Test
    void rejectsInvalidAuthorizerConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceAccessTokenAuthorizer(null, (clientId, kid) -> false));
        assertThrows(IllegalArgumentException.class,
                () -> new ServiceAccessTokenAuthorizer(signatureVerifier(), null));
    }

    private static ServiceAccessTokenAuthorizer authorizer(ServiceAccessTokenRevocationChecker revocations) {
        return new ServiceAccessTokenAuthorizer(signatureVerifier(), revocations);
    }

    private static ServiceAccessTokenSignatureVerifier signatureVerifier() {
        ServiceJwtVerificationKey publicKey = new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString());
        return new ServiceAccessTokenSignatureVerifier(
                kid -> key.getKeyID().equals(kid) ? Optional.of(publicKey) : Optional.empty(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                "https://iam.test",
                "saasforge-api",
                Duration.ofSeconds(30));
    }

    private static String token(String scope) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test")
                .audience("saasforge-api")
                .issueTime(Date.from(NOW.minusSeconds(1)))
                .expirationTime(Date.from(NOW.plusSeconds(299)))
                .jwtID(JTI.toString())
                .subject(CLIENT_ID.toString())
                .claim("client_id", CLIENT_ID.toString())
                .claim("scope", scope)
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(new JOSEObjectType("at+jwt"))
                        .keyID(key.getKeyID())
                        .build(),
                claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
