package io.saas.forge.starter.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.saas.forge.contracts.route.HttpRouteCatalog;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKey;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKeyResolver;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class RedisAuthenticationHttpIT {
    private static final String ID = "018f5f2a-7b3c-7def-8123-456789abcdef";
    private static final String MEMBER = "018f5f2a-7b3c-7def-8123-456789abcdea";
    private static final String TENANT = "018f5f2a-7b3c-7def-8123-456789abcdeb";
    private static final String JTI = "018f5f2a-7b3c-7def-8123-456789abcded";
    private static final String PREFIX = "sf:test:iam-service:";
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1").withExposedPorts(6379);
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private MockMvc http;
    private String userToken;
    private String serviceToken;

    @BeforeEach
    void start() throws Exception {
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        var revocations = new RedisReceiverTokenRevocationChecker(redis, "test");
        RSAKey key = new RSAKeyGenerator(2048).keyID("active").generate();
        ServiceJwtVerificationKeyResolver keys = kid -> Optional.of(new ServiceJwtVerificationKey(
                key.getKeyID(), key.getModulus().toString(), key.getPublicExponent().toString()));
        var user = new UserAccessTokenSignatureVerifier(keys, Clock.systemUTC(), "issuer", "saas.forge-api", Duration.ofSeconds(30));
        var service = new ServiceAccessTokenSignatureVerifier(keys, Clock.systemUTC(), "issuer", "saas.forge-api", Duration.ofSeconds(30));
        var routes = new ReceiverRouteCatalog(new HttpRouteCatalog(1, List.of(
                route("user", HttpRouteCatalog.CredentialRequirement.USER_REQUIRED),
                route("service", HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED))), "receiver");
        http = MockMvcBuilders.standaloneSetup(new Receiver())
                .addFilters(new HttpReceiverAuthenticationFilter(routes, new ReceiverTokenAuthenticators(
                        user::verify, revocations::isUserTokenRevoked, service::verify, revocations::isServiceTokenRevoked),
                        new ReceiverProblemDetailsWriter(new ObjectMapper()))).build();
        userToken = token(key, false);
        serviceToken = token(key, true);
    }

    @AfterEach
    void stop() { connection.destroy(); }

    @Test
    void emptyIndexDoesNotGrantAccessAndRebuiltIndexRestoresRequests() throws Exception {
        redis.delete(PREFIX + "revocation-index-ready:v1:state");
        http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isServiceUnavailable());
        http.perform(get("/service").header("Authorization", serviceToken)).andExpect(status().isServiceUnavailable());
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");
        http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isOk());
        http.perform(get("/service").header("Authorization", serviceToken)).andExpect(status().isOk());
    }

    @Test
    void everyPublishedUserRevocationAndFenceRejectsAnOtherwiseValidRequest() throws Exception {
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");
        for (String suffix : List.of("jwt-jti-revocation:v1:" + digest(JTI),
                "signing-kid-revocation:v1:" + digest("active"),
                "user-session-revocation-fence:v1:membership:" + MEMBER,
                "user-session-revocation-fence:v1:tenant:" + TENANT)) {
            http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isOk());
            redis.opsForValue().set(PREFIX + suffix, "1");
            try {
                http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isUnauthorized());
            } finally { redis.delete(PREFIX + suffix); }
        }
    }

    @Test
    void redisReadFailureRejectsCachedValidTokensAndRecovers() throws Exception {
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");
        http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isOk());
        assertThat(REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "-mget").getExitCode()).isZero();
        try {
            http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isServiceUnavailable());
            http.perform(get("/service").header("Authorization", serviceToken)).andExpect(status().isServiceUnavailable());
        } finally {
            assertThat(REDIS.execInContainer("redis-cli", "ACL", "SETUSER", "default", "+mget").getExitCode()).isZero();
        }
        http.perform(get("/user").header("Authorization", userToken)).andExpect(status().isOk());
        http.perform(get("/service").header("Authorization", serviceToken)).andExpect(status().isOk());
    }

    @Test
    void clientRevocationRejectsServiceWithoutCreatingUserContext() throws Exception {
        redis.opsForValue().set(PREFIX + "revocation-index-ready:v1:state", "1");
        http.perform(get("/service").header("Authorization", serviceToken)).andExpect(status().isOk());
        redis.opsForValue().set(PREFIX + "oauth-client-revocation:v1:" + ID, "1");
        try {
            http.perform(get("/service").header("Authorization", serviceToken)).andExpect(status().isUnauthorized());
        } finally { redis.delete(PREFIX + "oauth-client-revocation:v1:" + ID); }
    }

    private static HttpRouteCatalog.Route route(String path, HttpRouteCatalog.CredentialRequirement requirement) {
        return new HttpRouteCatalog.Route(path, HttpRouteCatalog.HttpMethod.GET, "/" + path, "receiver", requirement,
                path.equals("service") ? List.of("runtime:read") : List.of());
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String token(RSAKey key, boolean service) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("issuer").audience("saas.forge-api")
                .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900))).jwtID(JTI);
        if (service) { claims.subject(ID).claim("client_id", ID).claim("scope", "runtime:read"); }
        else { claims.claim("identityId", ID).claim("membershipId", MEMBER).claim("tenantId", TENANT); }
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(service ? new JOSEObjectType("at+jwt") : JOSEObjectType.JWT).keyID("active").build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return "Bearer " + jwt.serialize();
    }

    @RestController
    static class Receiver {
        @GetMapping("/user")
        String user() { return new SpringSecurityTenantContextAccessor().requireCurrent().tenantId().toString(); }
        @GetMapping("/service")
        String service() {
            assertThat(new SpringSecurityIdentityContextAccessor().current()).isEmpty();
            return new SpringSecurityServiceContextAccessor().current().orElseThrow().clientId().toString();
        }
    }
}
