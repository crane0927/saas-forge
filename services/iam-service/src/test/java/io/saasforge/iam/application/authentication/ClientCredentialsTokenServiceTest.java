package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ClientCredentialsTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");
    private static final UUID CLIENT_ID = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final String SECRET = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);

    private final OAuthClientRepository clients = mock(OAuthClientRepository.class);
    private ClientCredentialsTokenService service;
    private OAuthClient activeClient;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        activeClient = OAuthClient.register(
                        "tenant-access-service",
                        Set.of(OAuthScope.IAM_IDENTITY_WRITE, OAuthScope.IAM_PLATFORM_ROLE_READ),
                        NOW.minusSeconds(60))
                .identifiedBy(CLIENT_ID);
        StubSigningKeyRepository keys = new StubSigningKeyRepository();
        keys.activeKeys(List.of(SigningKey.restore(
                UUID.randomUUID(), "active-kid", "kms/key/1", "modulus", "AQAB", SigningKeyStatus.ACTIVE,
                NOW.minusSeconds(600), NOW.minusSeconds(300), null, null, null)));
        ServiceAccessTokenIssuer issuer = new ServiceAccessTokenIssuer(
                new JwtSigningService(new ActiveSigningKeyResolver(keys),
                        (keyReference, algorithm, input) -> new byte[32]),
                new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), clock,
                "https://iam.test", Duration.ofMinutes(5));
        service = new ClientCredentialsTokenService(clients, issuer, clock);
    }

    @Test
    void issuesOnlyRequestedGrantedScopesInSortedOrder() {
        when(clients.findActiveBySecretDigest(any(), any())).thenReturn(Optional.of(activeClient));

        IssuedServiceAccessToken token = service.issue(
                CLIENT_ID, SECRET, "client_credentials", "iam:platform-role:read iam:identity:write");

        assertEquals("iam:identity:write iam:platform-role:read", token.scope());
        assertEquals(300, token.expiresInSeconds());
    }

    @Test
    void rejectsWrongSecretRevokedClientAndWrongClientId() {
        when(clients.findActiveBySecretDigest(ClientSecretDigest.fromPlaintext(SECRET), NOW))
                .thenReturn(Optional.empty());
        assertThrows(ClientCredentialsInvalidException.class,
                () -> service.issue(CLIENT_ID, SECRET, "client_credentials", null));

        when(clients.findActiveBySecretDigest(ClientSecretDigest.fromPlaintext(SECRET), NOW))
                .thenReturn(Optional.of(activeClient));
        assertThrows(ClientCredentialsInvalidException.class,
                () -> service.issue(
                        UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90"),
                        SECRET, "client_credentials", null));
        assertThrows(ClientCredentialsInvalidException.class,
                () -> service.issue(CLIENT_ID, "not-the-secret", "client_credentials", null));
    }

    @Test
    void rejectsUnsupportedGrantAndOverprivilegedScope() {
        when(clients.findActiveBySecretDigest(any(), any())).thenReturn(Optional.of(activeClient));

        assertThrows(ClientCredentialsGrantInvalidException.class,
                () -> service.issue(CLIENT_ID, SECRET, "password", null));
        assertThrows(ClientCredentialsScopeRejectedException.class,
                () -> service.issue(CLIENT_ID, SECRET, "client_credentials", "entitlement:quota:write"));
    }
}
