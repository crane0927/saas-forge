package io.saasforge.iam.infrastructure.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ReservedIamServiceAccessTokenProviderTest {
    private static final String CLIENT_ID = "019535d9-0000-7000-8000-000000000001";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void obtainsExactMembershipReadScopeAndCachesOnlyTheToken() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReservedIamServiceAccessTokenProvider provider = provider(builder, CLIENT_ID, "secret");
        server.expect(requestTo("http://iam/oauth2/token"))
                .andExpect(content().string(
                        "grant_type=client_credentials&scope=tenant-access%3Amembership%3Aread"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token\",\"expires_in\":60,"
                                + "\"scope\":\"tenant-access:membership:read\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals("token", provider.membershipReadToken());
        assertEquals("token", provider.membershipReadToken());
        server.verify();
    }

    @Test
    void rejectsInvalidCredentialsAndTokenResponses() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> provider(RestClient.builder(), UUID.randomUUID().toString(), "secret")
                        .membershipReadToken());
        assertThrows(IllegalStateException.class,
                () -> provider(RestClient.builder(), CLIENT_ID.toUpperCase(), "secret")
                        .membershipReadToken());
        assertThrows(IllegalStateException.class,
                () -> provider(RestClient.builder(), CLIENT_ID, " ").membershipReadToken());

        RestClient.Builder builder = RestClient.builder().baseUrl("http://iam");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReservedIamServiceAccessTokenProvider provider = provider(builder, CLIENT_ID, "secret");
        server.expect(requestTo("http://iam/oauth2/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"token\",\"expires_in\":60,\"scope\":\"wrong\"}",
                        MediaType.APPLICATION_JSON));
        assertThrows(IllegalStateException.class, provider::membershipReadToken);
    }

    private ReservedIamServiceAccessTokenProvider provider(
            RestClient.Builder builder, String clientId, String secret) throws Exception {
        Path idFile = Files.writeString(directory.resolve("id-" + directory.toFile().list().length), clientId);
        Path secretFile = Files.writeString(
                directory.resolve("secret-" + directory.toFile().list().length), secret);
        return new ReservedIamServiceAccessTokenProvider(builder.build(), idFile, secretFile, CLOCK);
    }
}
