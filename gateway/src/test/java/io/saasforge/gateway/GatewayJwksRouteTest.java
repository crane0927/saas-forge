package io.saasforge.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayJwksRouteTest {

    private static final HttpServer IAM_SERVER = startServer("iam");
    private static final HttpServer TENANT_ACCESS_SERVER = startServer("tenant-access");
    private static final HttpServer ENTITLEMENT_SERVER = startServer("entitlement");
    private static final URI IAM_URI = URI.create("http://127.0.0.1:" + IAM_SERVER.getAddress().getPort());
    private static final URI TENANT_ACCESS_URI = URI.create(
            "http://127.0.0.1:" + TENANT_ACCESS_SERVER.getAddress().getPort());
    private static final URI ENTITLEMENT_URI = URI.create("http://127.0.0.1:" + ENTITLEMENT_SERVER.getAddress().getPort());

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void gatewayTargets(DynamicPropertyRegistry registry) {
        registry.add("gateway.targets.iam", () -> IAM_URI.toString());
        registry.add("gateway.targets.tenant-access", () -> TENANT_ACCESS_URI.toString());
        registry.add("gateway.targets.entitlement", () -> ENTITLEMENT_URI.toString());
    }

    @AfterAll
    static void stopIamServer() {
        IAM_SERVER.stop(0);
        TENANT_ACCESS_SERVER.stop(0);
        ENTITLEMENT_SERVER.stop(0);
    }

    @Test
    void proxiesJwksFromIam() throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", "/.well-known/jwks.json");

        assertEquals(200, response.statusCode());
        assertEquals("application/json", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals("iam", response.body());
    }

    @Test
    void routesEveryDeclaredOperationToItsOwningService() throws IOException, InterruptedException {
        for (RouteExpectation route : List.of(
                route("POST", "/api/v1/auth/login", "iam"),
                route("POST", "/api/v1/auth/refresh", "iam"),
                route("POST", "/api/v1/auth/logout", "iam"),
                route("POST", "/api/v1/auth/tenant-switches", "iam"),
                route("POST", "/oauth2/token", "iam"),
                route("GET", "/.well-known/jwks.json", "iam"),
                route("POST", "/api/v1/platform/tenants", "tenant-access"),
                route("POST", "/api/v1/platform/tenants/018f2d3a-4b5c-7d6e-8f90-123456789abc/administrator-initializations",
                        "tenant-access"),
                route("POST", "/api/v1/platform/tenants/018f2d3a-4b5c-7d6e-8f90-123456789abc/suspensions", "tenant-access"),
                route("DELETE", "/api/v1/platform/tenants/018f2d3a-4b5c-7d6e-8f90-123456789abc/suspensions", "tenant-access"),
                route("POST", "/api/v1/platform/quota-definitions", "entitlement"),
                route("POST", "/api/v1/platform/quota-definitions/018f2d3a-4b5c-7d6e-8f90-123456789abc/activations",
                        "entitlement"),
                route("POST", "/api/v1/platform/plans", "entitlement"),
                route("POST", "/api/v1/platform/plans/018f2d3a-4b5c-7d6e-8f90-123456789abc/activations", "entitlement"),
                route("POST", "/api/v1/platform/tenants/018f2d3a-4b5c-7d6e-8f90-123456789abc/subscriptions", "entitlement"),
                route("POST", "/api/v1/platform/oauth-clients", "iam"),
                route("POST", "/api/v1/platform/oauth-clients/018f2d3a-4b5c-7d6e-8f90-123456789abc/secret-rotations", "iam"),
                route("POST", "/api/v1/platform/oauth-clients/018f2d3a-4b5c-7d6e-8f90-123456789abc/revocations", "iam"))) {
            assertEquals(route.service(), send(route.method(), route.path()).body(), route.path());
        }
    }

    @Test
    void rejectsUndeclaredRouteAndMethodWithProblemDetails() throws IOException, InterruptedException {
        HttpResponse<String> unknownRoute = send("GET", "/not-declared");
        assertEquals(404, unknownRoute.statusCode());
        assertTrue(unknownRoute.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("application/problem+json"));
        assertTrue(unknownRoute.body().contains("\"code\":\"ROUTE_NOT_FOUND\""));

        HttpResponse<String> unsupportedMethod = send("PUT",
                "/api/v1/platform/tenants/018f2d3a-4b5c-7d6e-8f90-123456789abc/suspensions");
        assertEquals(405, unsupportedMethod.statusCode());
        assertEquals("DELETE, POST", unsupportedMethod.headers().firstValue("Allow").orElseThrow());
        assertTrue(unsupportedMethod.body().contains("\"code\":\"METHOD_NOT_ALLOWED\""));
    }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + gatewayPort + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static RouteExpectation route(String method, String path, String service) {
        return new RouteExpectation(method, path, service);
    }

    private static HttpServer startServer(String service) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = service.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start the gateway target test server", exception);
        }
    }

    private record RouteExpectation(String method, String path, String service) {
    }
}
