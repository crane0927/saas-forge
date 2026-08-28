package io.saasforge.tenantaccess.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.saasforge.sdk.auth.PlatformRequestAuthorizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SecurityAdapterTest {
    private static final String CLIENT_ID = "019535d9-0000-7000-8000-000000000001";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void obtainsCachesAndSeparatesEveryServiceScope() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IamServiceAccessTokenProvider provider = provider(builder, CLIENT_ID, "secret");
        expectToken(server, "iam:platform-role:read", "platform", 60);
        expectToken(server, "iam:identity:write", "identity", 1);
        expectToken(server, "iam:password-setup:write", "password", 60);
        expectToken(server, "entitlement:quota:write", "quota", 60);

        assertEquals("platform", provider.token());
        assertEquals("platform", provider.token());
        assertEquals("identity", provider.identityWriteToken());
        assertEquals("password", provider.passwordSetupWriteToken());
        assertEquals("quota", provider.quotaWriteToken());
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("invalidTokenResponses")
    void rejectsInvalidTokenResponses(String response) throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IamServiceAccessTokenProvider provider = provider(builder, CLIENT_ID, "secret");
        server.expect(requestTo("http://iam/oauth2/token"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

        assertThrows(IllegalStateException.class, provider::token);
    }

    @Test
    void rejectsInvalidOrUnreadableClientSecrets() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> provider(RestClient.builder(), UUID.randomUUID().toString(), "secret").token());
        assertThrows(IllegalStateException.class,
                () -> provider(RestClient.builder(), CLIENT_ID.toUpperCase(), "secret").token());
        assertThrows(IllegalStateException.class,
                () -> provider(RestClient.builder(), CLIENT_ID, "   ").token());

        Path clientId = Files.writeString(directory.resolve("missing-secret-client-id"), CLIENT_ID);
        IamServiceAccessTokenProvider provider = new IamServiceAccessTokenProvider(
                RestClient.create(), clientId, directory.resolve("missing"), CLOCK);
        assertThrows(IllegalStateException.class, provider::token);
    }

    @Test
    void resolvesOnlyMatchingRs256JwksKeysAndRejectsInvalidDocuments() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        IamJwksKeyResolver resolver = new IamJwksKeyResolver(builder.build());
        server.expect(requestTo("http://iam/.well-known/jwks.json")).andRespond(withSuccess(
                "{\"keys\":["
                        + "{\"kty\":\"EC\",\"alg\":\"RS256\",\"kid\":\"target\",\"n\":\"n\",\"e\":\"e\"},"
                        + "{\"kty\":\"RSA\",\"alg\":\"RS512\",\"kid\":\"target\",\"n\":\"n\",\"e\":\"e\"},"
                        + "{\"kty\":\"RSA\",\"alg\":\"RS256\",\"kid\":\"other\",\"n\":\"n\",\"e\":\"e\"},"
                        + "{\"kty\":\"RSA\",\"alg\":\"RS256\",\"kid\":\"target\",\"n\":\"modulus\",\"e\":\"AQAB\"}]}",
                MediaType.APPLICATION_JSON));

        var key = resolver.findByKid("target").orElseThrow();
        assertEquals("target", key.kid());
        server.verify();

        assertInvalidJwks("");
        assertInvalidJwks("{}");
    }

    @Test
    void checksBothRevocationKeysAndFailsClosedWithoutReadyProjection() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisUserAccessTokenRevocationChecker checker =
                new RedisUserAccessTokenRevocationChecker(redis, "test");

        when(values.multiGet(anyList())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(List.of("1", "only-two"));
        assertThrows(IllegalStateException.class, () -> checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("0", null, null));
        assertThrows(IllegalStateException.class, () -> checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", null, null));
        assertFalse(checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", "revoked", null));
        assertTrue(checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", null, "revoked"));
        assertTrue(checker.isRevoked(UUID.randomUUID(), "kid"));
    }

    @Test
    void checksServiceClientAndSigningKeyRevocationAndFailsClosedWithoutReadyProjection() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisServiceAccessTokenRevocationChecker checker =
                new RedisServiceAccessTokenRevocationChecker(redis, "test");

        when(values.multiGet(anyList())).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(List.of("1", "only-two"));
        assertThrows(IllegalStateException.class, () -> checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("0", null, null));
        assertThrows(IllegalStateException.class, () -> checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", null, null));
        assertFalse(checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", "revoked", null));
        assertTrue(checker.isRevoked(UUID.randomUUID(), "kid"));
        when(values.multiGet(anyList())).thenReturn(Arrays.asList("1", null, "revoked"));
        assertTrue(checker.isRevoked(UUID.randomUUID(), "kid"));
    }

    @Test
    void delegatesPlatformAdminAuthorizationToTheSdk() {
        PlatformRequestAuthorizer delegate = mock(PlatformRequestAuthorizer.class);
        UUID identityId = UUID.randomUUID();
        when(delegate.authorize("Bearer user-token", "PLATFORM_ADMIN")).thenReturn(identityId);

        assertEquals(identityId,
                new SdkPlatformAdminAuthorizer(delegate).authorize("Bearer user-token"));
        verify(delegate).authorize("Bearer user-token", "PLATFORM_ADMIN");
    }

    private IamServiceAccessTokenProvider provider(
            RestClient.Builder builder, String clientIdValue, String secretValue) throws Exception {
        Path clientId = Files.writeString(directory.resolve("client-id-" + directory.toFile().list().length),
                clientIdValue);
        Path secret = Files.writeString(directory.resolve("secret-" + directory.toFile().list().length), secretValue);
        return new IamServiceAccessTokenProvider(builder.build(), clientId, secret, CLOCK);
    }

    private static void expectToken(
            MockRestServiceServer server, String scope, String token, int expiresIn) {
        server.expect(requestTo("http://iam/oauth2/token"))
                .andExpect(content().string("grant_type=client_credentials&scope=" + scope.replace(":", "%3A")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn
                                + ",\"scope\":\"" + scope + "\"}",
                        MediaType.APPLICATION_JSON));
    }

    private void assertInvalidJwks(String response) {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://iam/.well-known/jwks.json"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class,
                () -> new IamJwksKeyResolver(builder.build()).findByKid("target"));
    }

    private static Stream<Arguments> invalidTokenResponses() {
        return Stream.of(
                Arguments.of(""),
                Arguments.of("{\"expires_in\":60,\"scope\":\"iam:platform-role:read\"}"),
                Arguments.of("{\"access_token\":\"   \",\"expires_in\":60,\"scope\":\"iam:platform-role:read\"}"),
                Arguments.of("{\"access_token\":\"token\",\"expires_in\":0,\"scope\":\"iam:platform-role:read\"}"),
                Arguments.of("{\"access_token\":\"token\",\"expires_in\":60,\"scope\":\"wrong\"}"));
    }
}
