package io.saasforge.sdk.core.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.saasforge.sdk.core.rest.api.AuthenticationApi;
import io.saasforge.sdk.core.rest.model.ClientCredentialsTokenResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GeneratedRestClientHttpTest {

    @Test
    void sendsClientCredentialsToTheExplicitGatewayAndDeserializesTheResponse() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/oauth2/token", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = ("{\"access_token\":\"service-access-token\",\"token_type\":\"Bearer\","
                    + "\"expires_in\":300,\"scope\":\"runtime:read\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            String gateway = "http://127.0.0.1:" + server.getAddress().getPort();
            String basic = Base64.getEncoder().encodeToString(
                    "sdk-client:sdk-test-secret".getBytes(StandardCharsets.UTF_8));
            ApiClient client = new ApiClient().setRequestInterceptor(
                    request -> request.header("Authorization", "Basic " + basic));
            client.updateBaseUri(gateway);

            ClientCredentialsTokenResponse response = new AuthenticationApi(client)
                    .issueClientCredentialsToken("client_credentials", "runtime:read");

            assertEquals("grant_type=client_credentials&scope=runtime%3Aread", requestBody.get());
            assertEquals("Basic c2RrLWNsaWVudDpzZGstdGVzdC1zZWNyZXQ=", authorization.get());
            assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
            assertEquals("service-access-token", response.getAccessToken());
            assertEquals(ClientCredentialsTokenResponse.TokenTypeEnum.BEARER, response.getTokenType());
            assertEquals(300, response.getExpiresIn());
            assertEquals("runtime:read", response.getScope());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsTransportFailureThroughTheGeneratedClientContract() throws IOException {
        int port;
        try (ServerSocket unavailable = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            port = unavailable.getLocalPort();
        }
        ApiClient client = new ApiClient()
                .setConnectTimeout(Duration.ofSeconds(1))
                .setRequestInterceptor(request -> request.header("Authorization", "Basic dGVzdDp0ZXN0"));
        client.updateBaseUri("http://127.0.0.1:" + port);

        ApiException failure = assertThrows(ApiException.class, () -> new AuthenticationApi(client)
                .issueClientCredentialsToken("client_credentials", null));

        assertInstanceOf(IOException.class, failure.getCause());
    }

    @Test
    void hasNoUsableDefaultDeploymentAddress() {
        assertEquals("https://api.example.invalid", new ApiClient().getBaseUri());
    }
}
