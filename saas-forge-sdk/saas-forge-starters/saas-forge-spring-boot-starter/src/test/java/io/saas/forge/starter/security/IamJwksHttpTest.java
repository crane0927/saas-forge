package io.saas.forge.starter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.saas.forge.contracts.route.HttpRouteCatalog;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class IamJwksHttpTest {
    private final AtomicInteger fetches = new AtomicInteger();
    private HttpServer iam;
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private IamJwksKeyResolver keys;
    private volatile boolean revoked;
    private RSAKey key;
    private MockMvc http;
    private volatile java.util.concurrent.CountDownLatch responseGate;
    private volatile java.util.concurrent.CountDownLatch requestArrived;
    private final TestClock clock = new TestClock();

    @BeforeEach
    void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("active").algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE).generate();
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/.well-known/jwks.json", exchange -> {
            fetches.incrementAndGet();
            if (requestArrived != null) { requestArrived.countDown(); }
            if (responseGate != null) {
                try { responseGate.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus.get(), body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        iam.start();
        var discovery = mock(LoadBalancerClient.class);
        when(discovery.choose("iam-service")).thenReturn(new DefaultServiceInstance(
                "iam-1", "iam-service", "127.0.0.1", iam.getAddress().getPort(), false));
        keys = new IamJwksKeyResolver(discovery, clock);
        var user = new UserAccessTokenSignatureVerifier(keys, Clock.systemUTC(), "issuer", "saas.forge-api", Duration.ofSeconds(30));
        var service = new ServiceAccessTokenSignatureVerifier(keys, Clock.systemUTC(), "issuer", "saas.forge-api", Duration.ofSeconds(30));
        var routes = new ReceiverRouteCatalog(new HttpRouteCatalog(1, List.of(new HttpRouteCatalog.Route(
                "identity", HttpRouteCatalog.HttpMethod.GET, "/identity", "receiver",
                HttpRouteCatalog.CredentialRequirement.USER_REQUIRED, List.of()))), "receiver");
        http = MockMvcBuilders.standaloneSetup(new IdentityController())
                .addFilters(new HttpReceiverAuthenticationFilter(routes,
                        new ReceiverTokenAuthenticators(user::verify, (jti, kid, membership, tenant) -> revoked,
                                service::verify, (client, kid) -> false), new ReceiverProblemDetailsWriter(new ObjectMapper())))
                .build();
    }

    @AfterEach
    void stop() { keys.close(); iam.stop(0); }

    @Test
    void reusesPublicKeysAcrossAuthenticatedRequests() throws Exception {
        String token = token(key);
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(fetches).hasValue(1);
    }

    @Test
    void discoversNewSigningKeyAfterControlledRefreshInterval() throws Exception {
        http.perform(get("/identity").header("Authorization", "Bearer " + token(key))).andExpect(status().isOk());
        key = new RSAKeyGenerator(2048).keyID("next").algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE).generate();
        clock.advance(Duration.ofSeconds(11));
        http.perform(get("/identity").header("Authorization", "Bearer " + token(key))).andExpect(status().isOk());
        assertThat(fetches).hasValue(2);
    }

    @Test
    void failsClosedAfterCacheExpiryAndRecoversWithoutExtendingFailedCache() throws Exception {
        String token = token(key);
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        responseStatus.set(503);
        clock.advance(Duration.ofSeconds(299));
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(1));
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        responseStatus.set(200);
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        clock.advance(Duration.ofSeconds(10));
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(fetches).hasValue(3);
    }

    @Test
    void randomUnknownKidsCannotBypassRefreshRateLimit() throws Exception {
        http.perform(get("/identity").header("Authorization", "Bearer " + token(key))).andExpect(status().isOk());
        for (int i = 0; i < 12; i++) {
            RSAKey unknown = new RSAKey.Builder(key).keyID("unknown-" + i).build();
            http.perform(get("/identity").header("Authorization", "Bearer " + token(unknown))).andExpect(status().isUnauthorized());
        }
        assertThat(fetches).hasValue(1);
    }

    @Test
    void cachedSigningKeyCannotBypassRevocation() throws Exception {
        String token = token(key);
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        revoked = true;
        http.perform(get("/identity").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        assertThat(fetches).hasValue(1);
    }

    @Test
    void coalescesConcurrentRefreshWithoutBlockingKnownKeys() throws Exception {
        String original = token(key);
        http.perform(get("/identity").header("Authorization", "Bearer " + original)).andExpect(status().isOk());
        key = new RSAKeyGenerator(2048).keyID("next").algorithm(JWSAlgorithm.RS256).keyUse(KeyUse.SIGNATURE).generate();
        String next = token(key);
        clock.advance(Duration.ofSeconds(11));
        requestArrived = new java.util.concurrent.CountDownLatch(1);
        responseGate = new java.util.concurrent.CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 4; i++) {
                calls.add(workers.submit(() -> http.perform(get("/identity").header("Authorization", "Bearer " + next))
                        .andReturn().getResponse().getStatus()));
            }
            assertThat(requestArrived.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            http.perform(get("/identity").header("Authorization", "Bearer " + original)).andExpect(status().isOk());
            responseGate.countDown();
            for (var call : calls) { assertThat(call.get(3, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200); }
            assertThat(fetches).hasValue(2);
        } finally {
            responseGate.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void timesOutUnavailablePublicKeyWithoutRetryingTheRequest() throws Exception {
        responseGate = new java.util.concurrent.CountDownLatch(1);
        try {
            http.perform(get("/identity").header("Authorization", "Bearer " + token(key)))
                    .andExpect(status().isServiceUnavailable());
            assertThat(fetches).hasValue(1);
        } finally {
            responseGate.countDown();
        }
    }

    @Test
    void readinessRecoversAfterIamAndRedisBecomeAvailable() {
        var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        var values = (org.springframework.data.redis.core.ValueOperations<String, String>)
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("sf:test:iam-service:revocation-index-ready:v1:state")).thenReturn("0");
        var readiness = new AuthenticationReadiness(keys, new RedisReceiverTokenRevocationChecker(redis, "test"));
        responseStatus.set(503);
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("DOWN");
        responseStatus.set(200);
        clock.advance(Duration.ofSeconds(10));
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("DOWN");
        when(values.get("sf:test:iam-service:revocation-index-ready:v1:state")).thenReturn("1");
        assertThat(readiness.health().getStatus().getCode()).isEqualTo("UP");
    }

    static class TestClock extends Clock {
        private Instant now = Instant.now();
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private static String token(RSAKey key) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("issuer").audience("saas.forge-api")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
                .jwtID("018f5f2a-7b3c-7def-8123-456789abcded")
                .claim("identityId", "018f5f2a-7b3c-7def-8123-456789abcdef").build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                .keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @RestController
    static class IdentityController {
        @GetMapping("/identity")
        String identity() { return new SpringSecurityIdentityContextAccessor().current().orElseThrow().identityId().toString(); }
    }
}
