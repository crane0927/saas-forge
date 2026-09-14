package io.saasforge.iam.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import io.saasforge.iam.support.StubSigningKeyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NimbusPresentedAccessTokenVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final UUID JTI = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private RSAKey rsaKey;
    private NimbusPresentedAccessTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-kid").generate();
        SigningKey key = SigningKey.restore(
                UUID.randomUUID(), "test-kid", "test/key/1",
                rsaKey.getModulus().toString(), rsaKey.getPublicExponent().toString(),
                SigningKeyStatus.ACTIVE, NOW.minusSeconds(600), NOW.minusSeconds(300), null, null, null);
        StubSigningKeyRepository repository = new StubSigningKeyRepository();
        repository.publishedVerificationKeys(List.of(key));
        verifier = new NimbusPresentedAccessTokenVerifier(
                repository, Clock.fixed(NOW, ZoneOffset.UTC), "https://iam.test.saasforge.invalid");
    }

    @Test
    void verifiesSignedMinimalUserToken() throws Exception {
        String token = token("https://iam.test.saasforge.invalid", false);

        var verified = verifier.verify("Bearer " + token).orElseThrow();

        assertEquals(JTI, verified.jti());
        assertEquals("test-kid", verified.kid());
        assertEquals(NOW.plusSeconds(900), verified.expiresAt());
    }

    @Test
    void ignoresInvalidIssuerAndTamperedSignature() throws Exception {
        assertTrue(verifier.verify("Bearer " + token("https://attacker.invalid", false)).isEmpty());

        assertTrue(verifier.verify("Bearer " + tamperSignature(
                token("https://iam.test.saasforge.invalid", false))).isEmpty());
    }

    private String token(String issuer, boolean tenant) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience("saasforge-api")
                .issueTime(Date.from(NOW))
                .expirationTime(Date.from(NOW.plusSeconds(900)))
                .claim("identityId", "0198c9d5-0f25-7000-8000-000000000001")
                .jwtID(JTI.toString());
        if (tenant) {
            claims.claim("membershipId", "0198c9d5-0f25-7000-8000-000000000002")
                    .claim("tenantId", "0198c9d5-0f25-7000-8000-000000000003");
        }
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID("test-kid").build(),
                claims.build());
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    private static String tamperSignature(String token) {
        String[] segments = token.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
        signature[0] ^= 1;
        segments[2] = Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        return String.join(".", segments);
    }
}
