package io.saasforge.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "saasforge.gateway.configuration-revision=test"
})
@Import(GatewayTestDiscoveryConfiguration.class)
@ActiveProfiles("gateway-test")
class GatewayJwksRouteTest {

    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private static final Map<String, AtomicReference<ObservedRequest>> OBSERVED_REQUESTS = new ConcurrentHashMap<>();

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
    static void discoverServices(DynamicPropertyRegistry registry) {
        GatewayTestDiscoveryConfiguration.discoverAt(GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID, IAM_URI);
        GatewayTestDiscoveryConfiguration.discoverAt(
                GatewayTestDiscoveryConfiguration.TENANT_ACCESS_SERVICE_ID, TENANT_ACCESS_URI);
        GatewayTestDiscoveryConfiguration.discoverAt(
                GatewayTestDiscoveryConfiguration.ENTITLEMENT_SERVICE_ID, ENTITLEMENT_URI);
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
    void returnsGateway503WhenNoHealthyOwningServiceInstanceExists()
            throws IOException, InterruptedException {
        for (UnavailableRoute route : List.of(
                unavailableRoute(GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID, IAM_URI,
                        "GET", "/.well-known/jwks.json"),
                unavailableRoute(GatewayTestDiscoveryConfiguration.TENANT_ACCESS_SERVICE_ID, TENANT_ACCESS_URI,
                        "POST", "/api/v1/platform/tenants"),
                unavailableRoute(GatewayTestDiscoveryConfiguration.ENTITLEMENT_SERVICE_ID, ENTITLEMENT_URI,
                        "POST", "/api/v1/platform/quota-definitions"))) {
            GatewayTestDiscoveryConfiguration.clearInstances(route.serviceId());
            HttpResponse<String> response;
            try {
                response = send(route.method(), route.path());
            } finally {
                GatewayTestDiscoveryConfiguration.discoverAt(route.serviceId(), route.uri());
            }

            assertEquals(503, response.statusCode(), route.serviceId());
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow()
                    .startsWith("application/problem+json"));
            assertTrue(response.body().contains("\"code\":\"UPSTREAM_UNAVAILABLE\""));
        }
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
            resetObservedRequest(route.service());
            String query = "acceptanceOperation=" + route.path().substring(route.path().lastIndexOf('/') + 1);
            String body = "POST".equals(route.method()) ? "{\"operation\":\"" + route.service() + "\"}" : "";
            HttpRequest.Builder request = HttpRequest.newBuilder(gatewayUri(route.path() + "?" + query));
            if (body.isEmpty()) {
                request.method(route.method(), HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json")
                        .method(route.method(), HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response = send(request.build());

            assertEquals(200, response.statusCode(), route.path());
            assertEquals(route.service(), response.body(), route.path());
            assertEquals(route.service(), response.headers().firstValue("X-Acceptance-Service").orElseThrow(), route.path());
            ObservedRequest observed = observedRequest(route.service());
            assertEquals(route.method(), observed.method(), route.path());
            assertEquals(route.path() + "?" + query, observed.pathAndQuery(), route.path());
            assertEquals(body, observed.body(), route.path());
        }
    }

    @Test
    void rejectsUndeclaredRouteAndMethodWithProblemDetails() throws IOException, InterruptedException {
        HttpResponse<String> unknownRoute = send("GET", "/not-declared");
        assertEquals(404, unknownRoute.statusCode());
        assertTrue(unknownRoute.headers().firstValue("Content-Type").orElseThrow()
                .startsWith("application/problem+json"));
        assertTrue(unknownRoute.body().contains("\"code\":\"ROUTE_NOT_FOUND\""));

        HttpResponse<String> auditRoute = send("GET", "/api/v1/audit");
        assertEquals(404, auditRoute.statusCode());
        assertTrue(auditRoute.body().contains("\"code\":\"ROUTE_NOT_FOUND\""));

        HttpResponse<String> unsupportedMethod = send("PUT",
                "/api/v1/platform/tenants/018f2d3a-4b5c-7d6e-8f90-123456789abc/suspensions");
        assertEquals(405, unsupportedMethod.statusCode());
        assertEquals("DELETE, POST", unsupportedMethod.headers().firstValue("Allow").orElseThrow());
        assertTrue(unsupportedMethod.body().contains("\"code\":\"METHOD_NOT_ALLOWED\""));
    }

    @Test
    void continuesValidTraceContextAndTracestate() throws IOException, InterruptedException {
        resetObservedRequest("iam");
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        String tracestate = "acme=vendor,state=active";

        HttpResponse<String> response = send(HttpRequest.newBuilder(gatewayUri("/.well-known/jwks.json"))
                .header("traceparent", traceparent)
                .header("tracestate", tracestate)
                .GET()
                .build());

        assertEquals(200, response.statusCode());
        ObservedRequest observed = observedRequest("iam");
        assertEquals(traceparent, observed.firstHeader("traceparent"));
        assertEquals(tracestate, observed.firstHeader("tracestate"));
        assertFalse(observed.hasHeader("X-Identity"));
        assertFalse(observed.hasHeader("X-Membership"));
        assertFalse(observed.hasHeader("X-Tenant-Context"));
        assertFalse(observed.hasHeader("X-Correlation-Id"));
    }

    @Test
    void createsTraceContextForMissingOrInvalidInputAndUsesItForGatewayErrors()
            throws IOException, InterruptedException {
        resetObservedRequest("iam");
        send("POST", "/api/v1/auth/login");
        assertTrue(TRACEPARENT.matcher(observedRequest("iam").firstHeader("traceparent")).matches());

        resetObservedRequest("iam");
        send(HttpRequest.newBuilder(gatewayUri("/api/v1/auth/login"))
                .header("traceparent", "00-00000000000000000000000000000000-0000000000000000-01")
                .header("tracestate", "discarded=with-invalid-parent")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        ObservedRequest regenerated = observedRequest("iam");
        assertTrue(TRACEPARENT.matcher(regenerated.firstHeader("traceparent")).matches());
        assertFalse(regenerated.hasHeader("tracestate"));

        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        HttpResponse<String> error = send(HttpRequest.newBuilder(gatewayUri("/not-declared"))
                .header("traceparent", traceparent)
                .GET()
                .build());
        assertEquals(404, error.statusCode());
        assertTrue(error.body().contains("\"traceId\":\"4bf92f3577b34da6a3ce929d0e0e4736\""));
    }

    @Test
    void preservesRequestSemanticsAndAllowedBusinessHeaders() throws IOException, InterruptedException {
        resetObservedRequest("iam");
        HttpResponse<String> response = send(HttpRequest.newBuilder(gatewayUri("/api/v1/auth/login?source=portal"))
                .header("Content-Type", "application/json")
                .header("X-Request-Source", "portal")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"alice\"}"))
                .build());

        assertEquals(200, response.statusCode());
        ObservedRequest observed = observedRequest("iam");
        assertEquals("POST", observed.method());
        assertEquals("/api/v1/auth/login?source=portal", observed.pathAndQuery());
        assertEquals("{\"username\":\"alice\"}", observed.body());
        assertEquals("portal", observed.firstHeader("X-Request-Source"));
        assertTrue(observed.firstHeader("Content-Type").startsWith("application/json"));
    }

    @Test
    void removesClientSuppliedForwardingAndHopByHopHeaders() throws IOException, InterruptedException {
        resetObservedRequest("iam");
        HttpResponse<String> response = send(HttpRequest.newBuilder(gatewayUri("/.well-known/jwks.json"))
                .header("Forwarded", "for=198.51.100.24;host=attacker.example;proto=https")
                .header("X-Forwarded-For", "198.51.100.24")
                .header("X-Forwarded-Host", "attacker.example")
                .header("Proxy-Authorization", "Basic Y2xpZW50OnNlY3JldA==")
                .GET()
                .build());

        assertEquals(200, response.statusCode());
        ObservedRequest observed = observedRequest("iam");
        assertFalse(observed.hasHeader("Forwarded"));
        assertFalse(observed.hasHeader("X-Forwarded-For"));
        assertFalse(observed.hasHeader("X-Forwarded-Host"));
        assertFalse(observed.hasHeader("Proxy-Authorization"));
        assertEquals("127.0.0.1:" + IAM_SERVER.getAddress().getPort(), observed.firstHeader("Host"));
    }

    private HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(gatewayUri(path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request);
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI gatewayUri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private void resetObservedRequest(String service) {
        OBSERVED_REQUESTS.computeIfAbsent(service, ignored -> new AtomicReference<>()).set(null);
    }

    private ObservedRequest observedRequest(String service) {
        ObservedRequest observed = OBSERVED_REQUESTS.computeIfAbsent(service, ignored -> new AtomicReference<>()).get();
        if (observed == null) {
            throw new AssertionError(service + " downstream request was not observed");
        }
        return observed;
    }

    private static RouteExpectation route(String method, String path, String service) {
        return new RouteExpectation(method, path, service);
    }

    private static UnavailableRoute unavailableRoute(String serviceId, URI uri, String method, String path) {
        return new UnavailableRoute(serviceId, uri, method, path);
    }

    private static HttpServer startServer(String service) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                Map<String, List<String>> headers = new LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, List.copyOf(values)));
                OBSERVED_REQUESTS.computeIfAbsent(service, ignored -> new AtomicReference<>()).set(new ObservedRequest(
                        exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers,
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] body = service.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.getResponseHeaders().set("X-Acceptance-Service", service);
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

    private record UnavailableRoute(String serviceId, URI uri, String method, String path) {
    }

    private record ObservedRequest(String method, String pathAndQuery, Map<String, List<String>> headers, String body) {

        String firstHeader(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(Map.Entry::getValue)
                    .flatMap(List::stream)
                    .findFirst()
                    .orElse(null);
        }

        boolean hasHeader(String name) {
            return headers.keySet().stream().anyMatch(header -> header.equalsIgnoreCase(name));
        }
    }
}
