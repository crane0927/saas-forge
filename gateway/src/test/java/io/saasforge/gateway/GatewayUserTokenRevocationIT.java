package io.saasforge.gateway;

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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.loadbalancer.cache.enabled=false",
        "spring.data.redis.connect-timeout=PT1S",
        "spring.data.redis.timeout=PT1S",
        "saasforge.environment=test",
        "saasforge.gateway.configuration-revision=test",
        "security.jwt.issuer=https://iam.test.saasforge.invalid"
})
@Import(GatewayTestDiscoveryConfiguration.class)
@ActiveProfiles("gateway-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayUserTokenRevocationIT {

    private static final UUID IDENTITY_ID = uuidV7(1);
    private static final UUID MEMBERSHIP_ID = uuidV7(2);
    private static final UUID TENANT_ID = uuidV7(3);
    private static final UUID JTI = uuidV7(4);
    private static final String KID = "gateway-real-redis-kid";
    private static final RSAKey SIGNING_KEY = signingKey();
    private static final AtomicInteger IAM_REQUESTS = new AtomicInteger();
    private static final AtomicInteger TENANT_ACCESS_REQUESTS = new AtomicInteger();
    private static final HttpServer IAM_SERVER = startIamServer();
    private static final HttpServer TENANT_ACCESS_SERVER = startTargetServer(TENANT_ACCESS_REQUESTS);

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1"))
            .withCommand("redis-server", "--appendonly", "yes", "--maxmemory-policy", "noeviction")
            .withExposedPorts(6379);

    @LocalServerPort
    private int gatewayPort;

    @Autowired
    private StringRedisTemplate redis;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        GatewayTestDiscoveryConfiguration.discoverAt(
                GatewayTestDiscoveryConfiguration.IAM_SERVICE_ID, uri(IAM_SERVER));
        GatewayTestDiscoveryConfiguration.discoverAt(
                GatewayTestDiscoveryConfiguration.TENANT_ACCESS_SERVICE_ID, uri(TENANT_ACCESS_SERVER));
    }

    @BeforeEach
    void reset() {
        IAM_REQUESTS.set(0);
        TENANT_ACCESS_REQUESTS.set(0);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        redis.opsForValue().set(readyKey(), "1");
    }

    @AfterAll
    static void stopServers() {
        IAM_SERVER.stop(0);
        TENANT_ACCESS_SERVER.stop(0);
    }

    @Test
    @Order(1)
    void enforcesAnonymousOptionalAndRequiredRoutesBeforeForwarding() throws Exception {
        HttpResponse<String> anonymous = send(HttpRequest.newBuilder(gatewayUri("/.well-known/jwks.json"))
                .header("Authorization", "Bearer malformed")
                .GET()
                .build());
        assertEquals(200, anonymous.statusCode());

        int iamBeforeLogout = IAM_REQUESTS.get();
        HttpResponse<String> optional = send(HttpRequest.newBuilder(gatewayUri("/api/v1/auth/logout"))
                .header("Authorization", "Bearer malformed")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertEquals(200, optional.statusCode());
        assertEquals(iamBeforeLogout + 1, IAM_REQUESTS.get());

        HttpResponse<String> missing = send(HttpRequest.newBuilder(gatewayUri("/api/v1/platform/tenants"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
        assertInvalidWithoutForwarding(missing);

        HttpResponse<String> valid = requiredRequest(token(JTI, MEMBERSHIP_ID, TENANT_ID));
        assertEquals(200, valid.statusCode());
        assertEquals(1, TENANT_ACCESS_REQUESTS.get());
    }

    @Test
    @Order(2)
    void realRedisJtiKidAndOverlappingFencesRejectWithoutForwarding() throws Exception {
        for (String key : new String[] {
                jtiKey(JTI),
                kidKey(KID),
                tenantFenceKey(TENANT_ID),
                membershipFenceKey(MEMBERSHIP_ID)
        }) {
            redis.opsForValue().set(key, "1");
            HttpResponse<String> response = requiredRequest(token(JTI, MEMBERSHIP_ID, TENANT_ID));
            assertInvalidWithoutForwarding(response);
            redis.delete(key);
        }

        redis.opsForValue().set(tenantFenceKey(TENANT_ID), uuidV7(10).toString());
        redis.opsForValue().set(membershipFenceKey(MEMBERSHIP_ID), uuidV7(11).toString());
        assertInvalidWithoutForwarding(requiredRequest(token(JTI, MEMBERSHIP_ID, TENANT_ID)));

        UUID isolatedTenant = uuidV7(12);
        UUID isolatedMembership = uuidV7(13);
        HttpResponse<String> isolated = requiredRequest(token(uuidV7(14), isolatedMembership, isolatedTenant));
        assertEquals(200, isolated.statusCode());
        assertEquals(1, TENANT_ACCESS_REQUESTS.get());
    }

    @Test
    @Order(3)
    void readyFalseReturns503WithoutWwwAuthenticateOrForwarding() throws Exception {
        redis.opsForValue().set(readyKey(), "0");

        HttpResponse<String> response = requiredRequest(token(JTI, MEMBERSHIP_ID, TENANT_ID));

        assertEquals(503, response.statusCode());
        assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
        assertTrue(response.body().contains("\"code\":\"TOKEN_REVOCATION_STATUS_UNAVAILABLE\""));
        assertEquals(0, TENANT_ACCESS_REQUESTS.get());
    }

    @Test
    @Order(4)
    void realRedisOutageReturns503WithoutForwarding() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            HttpResponse<String> response = requiredRequest(token(JTI, MEMBERSHIP_ID, TENANT_ID));
            assertEquals(503, response.statusCode());
            assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
            assertTrue(response.body().contains("\"code\":\"TOKEN_REVOCATION_STATUS_UNAVAILABLE\""));
            assertEquals(0, TENANT_ACCESS_REQUESTS.get());
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    private void assertInvalidWithoutForwarding(HttpResponse<String> response) {
        assertEquals(401, response.statusCode());
        assertEquals("Bearer", response.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertTrue(response.body().contains("\"code\":\"ACCESS_TOKEN_INVALID\""));
        assertEquals(0, TENANT_ACCESS_REQUESTS.get());
    }

    private HttpResponse<String> requiredRequest(String token) throws Exception {
        return send(HttpRequest.newBuilder(gatewayUri("/api/v1/platform/tenants"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI gatewayUri(String path) {
        return URI.create("http://127.0.0.1:" + gatewayPort + path);
    }

    private static String token(UUID jti, UUID membershipId, UUID tenantId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer("https://iam.test.saasforge.invalid")
                .audience("saasforge-api")
                .issueTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("identityId", IDENTITY_ID.toString())
                .claim("membershipId", membershipId.toString())
                .claim("tenantId", tenantId.toString())
                .jwtID(jti.toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(KID)
                .build(), claims);
        jwt.sign(new RSASSASigner(SIGNING_KEY));
        return jwt.serialize();
    }

    private static HttpServer startIamServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                IAM_REQUESTS.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                String body = "/.well-known/jwks.json".equals(path)
                        ? "{\"keys\":[{\"kty\":\"RSA\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\""
                                + KID + "\",\"n\":\"" + SIGNING_KEY.getModulus()
                                + "\",\"e\":\"" + SIGNING_KEY.getPublicExponent() + "\"}]}"
                        : "iam";
                respond(exchange, body);
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动 Gateway IAM 测试服务", exception);
        }
    }

    private static HttpServer startTargetServer(AtomicInteger requests) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                respond(exchange, "tenant-access");
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动 Gateway 下游测试服务", exception);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static URI uri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static RSAKey signingKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KID).generate();
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Gateway 集成测试签名密钥", exception);
        }
    }

    private static UUID uuidV7(long suffix) {
        return UUID.fromString(String.format("0198c9d5-0f25-7000-8000-%012x", suffix));
    }

    private static String readyKey() {
        return "sf:test:iam-service:revocation-index-ready:v1:state";
    }

    private static String jtiKey(UUID jti) throws Exception {
        return "sf:test:iam-service:jwt-jti-revocation:v1:" + digest(jti.toString());
    }

    private static String kidKey(String kid) throws Exception {
        return "sf:test:iam-service:signing-kid-revocation:v1:" + digest(kid);
    }

    private static String tenantFenceKey(UUID tenantId) {
        return "sf:test:iam-service:user-session-revocation-fence:v1:tenant:" + tenantId;
    }

    private static String membershipFenceKey(UUID membershipId) {
        return "sf:test:iam-service:user-session-revocation-fence:v1:membership:" + membershipId;
    }

    private static String digest(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
