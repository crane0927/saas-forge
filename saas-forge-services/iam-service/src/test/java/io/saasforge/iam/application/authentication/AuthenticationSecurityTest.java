package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import io.saasforge.iam.support.StubSigningKeyRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthenticationSecurityTest {
    private static final Instant NOW = Instant.parse("2026-08-20T08:00:00Z");

    @Test
    void passwordVerificationUsesNfcAndRequiredArgon2idParameters() {
        Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
        Argon2idPasswordHash hash = Argon2idPasswordHash.of(encoder.encode("Caf\u00e9-password"));
        PasswordVerifier verifier = new PasswordVerifier();

        assertTrue(verifier.matches("Cafe\u0301-password", hash));
        assertFalse(verifier.matches("wrong-password", hash));
        verifier.dummyMatches("unknown-account-password");
    }

    @Test
    void accessTokenContainsOnlyApprovedHeaderAndPlatformClaims() {
        StubSigningKeyRepository keys = new StubSigningKeyRepository();
        keys.activeKeys(List.of(SigningKey.restore(
                UUID.randomUUID(), "active-kid", "kms/key/1", "modulus", "AQAB", SigningKeyStatus.ACTIVE,
                NOW.minusSeconds(600), NOW.minusSeconds(300), null, null, null)));
        JwtSigningService signing = new JwtSigningService(new ActiveSigningKeyResolver(keys),
                (keyReference, algorithm, input) -> new byte[32]);
        Clock clock = Clock.fixed(NOW.plusMillis(999), ZoneOffset.UTC);
        UserAccessTokenIssuer issuer = new UserAccessTokenIssuer(
                signing, new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), clock,
                "https://iam.example.test", Duration.ofMinutes(15), (membershipId, tenantId) -> { });

        UUID identityId = UUID.randomUUID();
        IssuedAccessToken token = issuer.issueUserToken(identityId, null, null);
        String[] segments = token.value().split("\\.");
        JsonNode header = json(segments[0]);
        JsonNode claims = json(segments[1]);

        assertEquals(Set.of("alg", "typ", "kid"), header.propertyNames());
        assertEquals("RS256", header.get("alg").asString());
        assertEquals("JWT", header.get("typ").asString());
        assertEquals("active-kid", header.get("kid").asString());
        assertEquals(Set.of("iss", "aud", "iat", "exp", "identityId", "jti"), claims.propertyNames());
        assertEquals("https://iam.example.test", claims.get("iss").asString());
        assertEquals("saasforge-api", claims.get("aud").asString());
        assertEquals(900, claims.get("exp").asLong() - claims.get("iat").asLong());
        assertEquals(identityId.toString(), claims.get("identityId").asString());
        assertEquals(7, UUID.fromString(claims.get("jti").asString()).version());
        assertEquals(claims.get("jti").asString(), claims.get("jti").asString().toLowerCase());
    }

    @Test
    void tenantAccessTokenAddsOnlyPairedMembershipAndTenantClaims() {
        StubSigningKeyRepository keys = new StubSigningKeyRepository();
        keys.activeKeys(List.of(SigningKey.restore(
                UUID.randomUUID(), "active-kid", "kms/key/1", "modulus", "AQAB", SigningKeyStatus.ACTIVE,
                NOW.minusSeconds(600), NOW.minusSeconds(300), null, null, null)));
        JwtSigningService signing = new JwtSigningService(new ActiveSigningKeyResolver(keys),
                (keyReference, algorithm, input) -> new byte[32]);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        UserAccessTokenIssuer issuer = new UserAccessTokenIssuer(
                signing, new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), clock,
                "https://iam.example.test", Duration.ofMinutes(15), (membershipId, tenantId) -> { });

        UUID identityId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IssuedAccessToken token = issuer.issueUserToken(identityId, membershipId, tenantId);
        JsonNode claims = json(token.value().split("\\.")[1]);

        assertEquals(Set.of("iss", "aud", "iat", "exp", "identityId", "membershipId", "tenantId", "jti"),
                claims.propertyNames());
        assertEquals(membershipId.toString(), claims.get("membershipId").asString());
        assertEquals(tenantId.toString(), claims.get("tenantId").asString());
    }

    @Test
    void serviceAccessTokenUsesAtJwtAndOnlySortedServiceClaims() {
        StubSigningKeyRepository keys = new StubSigningKeyRepository();
        keys.activeKeys(List.of(SigningKey.restore(
                UUID.randomUUID(), "active-kid", "kms/key/1", "modulus", "AQAB", SigningKeyStatus.ACTIVE,
                NOW.minusSeconds(600), NOW.minusSeconds(300), null, null, null)));
        JwtSigningService signing = new JwtSigningService(new ActiveSigningKeyResolver(keys),
                (keyReference, algorithm, input) -> new byte[32]);
        Clock clock = Clock.fixed(NOW.plusMillis(999), ZoneOffset.UTC);
        ServiceAccessTokenIssuer issuer = new ServiceAccessTokenIssuer(
                signing, new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), clock,
                "https://iam.example.test", Duration.ofMinutes(5));

        UUID clientId = uuidV7();
        IssuedServiceAccessToken token = issuer.issue(clientId, Set.of(
                io.saasforge.iam.domain.client.OAuthScope.IAM_PLATFORM_ROLE_READ,
                io.saasforge.iam.domain.client.OAuthScope.IAM_IDENTITY_WRITE));
        String[] segments = token.value().split("\\.");
        JsonNode header = json(segments[0]);
        JsonNode claims = json(segments[1]);

        assertEquals(Set.of("alg", "typ", "kid"), header.propertyNames());
        assertEquals("RS256", header.get("alg").asString());
        assertEquals("at+jwt", header.get("typ").asString());
        assertEquals(Set.of("iss", "aud", "iat", "exp", "jti", "sub", "client_id", "scope"),
                claims.propertyNames());
        assertEquals(clientId.toString(), claims.get("sub").asString());
        assertEquals(clientId.toString(), claims.get("client_id").asString());
        assertEquals("iam:identity:write iam:platform-role:read", claims.get("scope").asString());
        assertEquals(300, claims.get("exp").asLong() - claims.get("iat").asLong());
        assertEquals(7, UUID.fromString(claims.get("jti").asString()).version());
        assertFalse(claims.has("identityId"));
        assertFalse(claims.has("tenantId"));
        assertFalse(claims.has("membershipId"));
        assertFalse(claims.has("role"));
        assertFalse(claims.has("permission"));
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0198c9d50f257000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
    }

    private static JsonNode json(String encoded) {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(encoded));
    }
}
