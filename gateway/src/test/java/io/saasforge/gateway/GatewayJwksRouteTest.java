package io.saasforge.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayJwksRouteTest {

    private static final HttpServer IAM_SERVER = startIamServer();
    private static final URI IAM_URI = URI.create("http://127.0.0.1:" + IAM_SERVER.getAddress().getPort());

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void gatewayTargets(DynamicPropertyRegistry registry) {
        registry.add("gateway.targets.iam", () -> IAM_URI.toString());
        registry.add("gateway.targets.tenant-access", () -> IAM_URI.toString());
        registry.add("gateway.targets.entitlement", () -> IAM_URI.toString());
    }

    @AfterAll
    static void stopIamServer() {
        IAM_SERVER.stop(0);
    }

    @Test
    void proxiesJwksFromIam() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort
                + "/.well-known/jwks.json"))
                .GET()
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("{\"keys\":[]}", response.body());
    }

    private static HttpServer startIamServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start the IAM test server", exception);
        }
    }
}
