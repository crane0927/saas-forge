package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembershipQueryServiceGrpc;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsRequest;
import io.saasforge.contracts.tenantaccess.membership.v1.ListAccessibleMembershipsResponse;
import io.saasforge.iam.application.authentication.AccessibleMemberships;
import io.saasforge.iam.application.authentication.InitialPasswordChangeService;
import io.saasforge.iam.application.authentication.PasswordSetupChallengeToken;
import io.saasforge.iam.application.authentication.PasswordSetupService;
import io.saasforge.iam.application.authentication.PresentedAccessToken;
import io.saasforge.iam.application.authentication.PresentedAccessTokenVerifier;
import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.application.authentication.RevocationIndexRecovery;
import io.saasforge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saasforge.iam.application.signing.JwtSigningPort;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.application.signing.SigningKeyLifecycleService;
import io.saasforge.iam.application.signing.SigningKeyRevocationTransaction;
import io.saasforge.iam.config.AuthenticationConfiguration;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saasforge.iam.infrastructure.messaging.OutboxPublisher;
import io.saasforge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import io.saasforge.iam.infrastructure.grpc.GrpcAccessibleMemberships;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.ConversionService;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringJUnitConfig(AuthenticationHttpIT.TestConfiguration.class)
@TestPropertySource(properties = {
        "saasforge.environment=test",
        "security.jwt.issuer=https://iam.test.saasforge.invalid",
        "security.jwt.access-token-ttl=PT15M",
        "security.login-protection.failure-window=PT15M",
        "security.login-protection.maximum-failures=5",
        "security.login-protection.lock-duration=PT15M",
        "security.revocation-index.recovery-delay=PT1H",
        "saasforge.iam.outbox.publish-delay=PT0.1S"
})
@WebAppConfiguration
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticationHttpIT {
    private static final String REDIS_PASSWORD = "redis-auth-test-password";
    private static final String IDEMPOTENCY_KEY = "0198c9d5-0f25-7b21-8d67-31c8652d4c8f";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final Pattern COOKIE_VALUE = Pattern.compile("^__Host-sf_refresh=([^;]+)");
    private static final Path REPOSITORY_ROOT = repositoryRoot();
    private static final Map<UUID, List<io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership>>
            ACCESSIBLE_MEMBERSHIPS = new ConcurrentHashMap<>();
    private static final Set<UUID> TENANT_ACCESS_FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean SIGNING_FAILURE = new AtomicBoolean();
    private static final ManagedChannel TENANT_ACCESS_CHANNEL;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
            .withDatabaseName("saasforge")
            .withUsername("saasforge_admin")
            .withPassword("admin-password")
            .withEnv("IAM_MIGRATOR_PASSWORD", "iam-migrator-password")
            .withEnv("IAM_APP_PASSWORD", "iam-app-password")
            .withEnv("TENANT_ACCESS_MIGRATOR_PASSWORD", "tenant-access-migrator-password")
            .withEnv("TENANT_ACCESS_APP_PASSWORD", "tenant-access-app-password")
            .withEnv("ENTITLEMENT_MIGRATOR_PASSWORD", "entitlement-migrator-password")
            .withEnv("ENTITLEMENT_APP_PASSWORD", "entitlement-app-password")
            .withEnv("AUDIT_MIGRATOR_PASSWORD", "audit-migrator-password")
            .withEnv("AUDIT_APP_PASSWORD", "audit-app-password")
            .withCopyFileToContainer(
                    org.testcontainers.utility.MountableFile.forHostPath(
                            REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.8.1"))
            .withCommand("redis-server", "--appendonly", "yes", "--maxmemory-policy", "noeviction",
                    "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        migrateAndSeedSigningKey();
        try {
            String serverName = InProcessServerBuilder.generateName();
            Server ignored = InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new TenantAccessAuthority())
                    .build()
                    .start();
            TENANT_ACCESS_CHANNEL = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    IdentityRepository identities;

    @Autowired
    PlatformRoleAssignmentRepository platformRoles;

    @Autowired
    OAuthClientRepository oauthClients;

    @Autowired
    InitialPasswordChangeService passwordChangeService;

    @Autowired
    PasswordSetupService passwordSetupService;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    DataSource dataSource;

    @Autowired
    RevocationIndex revocationIndex;

    @Autowired
    RevocationIndexRecovery revocationIndexRecovery;

    @Autowired
    SigningKeyLifecycleService signingKeyLifecycleService;

    MockMvc mockMvc;
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        jdbc = new JdbcTemplate(dataSource);
        Set<String> keys = redis.keys("sf:test:iam-service:login-*:v1:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        ACCESSIBLE_MEMBERSHIPS.clear();
        TENANT_ACCESS_FAILURES.clear();
        SIGNING_FAILURE.set(false);
    }

    @Test
    @Order(1)
    void platformLoginIssuesMinimalJwtSecureCookieAndAtomicFactsThenPublishesOutbox() throws Exception {
        TestUser user = createUser("platform-success@example.test", "Caf\u00e9-password", true, Credential.REGULAR);
        try (KafkaConsumer<String, String> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of("saasforge.test.iam-service.events"));
            consumer.poll(Duration.ofMillis(250));

            MvcResult response = login("platform-success@example.test", "Cafe\u0301-password", "PLATFORM")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(header().exists("Set-Cookie"))
                    .andReturn();

            JsonNode body = json(response.getResponse().getContentAsByteArray());
            String token = body.get("accessToken").asString();
            String[] segments = token.split("\\.");
            JsonNode header = json(Base64.getUrlDecoder().decode(segments[0]));
            JsonNode claims = json(Base64.getUrlDecoder().decode(segments[1]));
            assertEquals(Set.of("alg", "typ", "kid"), header.propertyNames());
            assertEquals(Set.of("iss", "aud", "iat", "exp", "identityId", "jti"), claims.propertyNames());
            assertEquals("RS256", header.get("alg").asString());
            assertEquals("JWT", header.get("typ").asString());
            assertEquals("active-login-kid", header.get("kid").asString());
            assertEquals("https://iam.test.saasforge.invalid", claims.get("iss").asString());
            assertEquals("saasforge-api", claims.get("aud").asString());
            assertEquals(900, claims.get("exp").asLong() - claims.get("iat").asLong());
            assertEquals(user.identity().id().toString(), claims.get("identityId").asString());
            assertEquals(7, UUID.fromString(claims.get("jti").asString()).version());

            String setCookie = response.getResponse().getHeader("Set-Cookie");
            assertNotNull(setCookie);
            assertTrue(setCookie.contains("Secure"));
            assertTrue(setCookie.contains("HttpOnly"));
            assertTrue(setCookie.contains("SameSite=Strict"));
            assertTrue(setCookie.contains("Path=/"));
            assertTrue(setCookie.contains("Max-Age=1800"));
            assertFalse(setCookie.toLowerCase().contains("domain="));
            Matcher cookieMatcher = COOKIE_VALUE.matcher(setCookie);
            assertTrue(cookieMatcher.find());
            String refreshToken = cookieMatcher.group(1);

            Map<String, Object> facts = jdbc.queryForMap("""
                    SELECT family.family_purpose, token.token_digest, issuance.kid,
                           issuance.membership_id, issuance.tenant_id
                    FROM iam_refresh_token_families family
                    JOIN iam_refresh_tokens token ON token.family_id = family.id
                    JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                    WHERE family.identity_id = ?
                    """, user.identity().id());
            assertEquals("USER_PLATFORM", facts.get("family_purpose"));
            assertEquals("active-login-kid", facts.get("kid"));
            assertEquals(null, facts.get("membership_id"));
            assertEquals(null, facts.get("tenant_id"));
            assertArrayEquals(MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8)), (byte[]) facts.get("token_digest"));

            String snapshot = jdbc.queryForObject(
                    "SELECT event_snapshot::TEXT FROM iam_outbox_events WHERE ordering_key = ?",
                    String.class, user.identity().id().toString());
            assertNotNull(snapshot);
            assertFalse(snapshot.contains("platform-success@example.test"));
            assertFalse(snapshot.contains(refreshToken));
            assertFalse(snapshot.contains(token));

            ConsumerRecord<String, String> event = awaitEvent(consumer);
            assertEquals(user.identity().id().toString(), event.key());
            JsonNode eventJson = new ObjectMapper().readTree(event.value());
            assertEquals("com.saasforge.iam.session.started.v1", eventJson.get("type").asString());
            assertEquals(TRACE_ID, eventJson.get("traceId").asString());
            assertEquals("USER_PLATFORM", eventJson.at("/data/purpose").asString());
            assertEquals("ACCESS_TOKEN_ISSUED", eventJson.at("/data/result").asString());
        }
    }

    @Test
    @Order(2)
    void credentialFailuresAreUniformAndFifthFailureLocksWithoutCreatingSessionFacts() throws Exception {
        TestUser wrongPassword = createUser("wrong-password@example.test", "correct-password", true, Credential.REGULAR);
        TestUser noCredential = createUser("no-credential@example.test", "unused", true, Credential.NONE);
        TestUser expiredInitial = createUser("expired-initial@example.test", "initial-password", true, Credential.EXPIRED_INITIAL);
        TestUser locked = createUser("locked@example.test", "correct-password", true, Credential.REGULAR);

        assertAuthenticationFailed(login("unknown@example.test", "any-password", "PLATFORM").andReturn());
        assertAuthenticationFailed(login("wrong-password@example.test", "wrong", "PLATFORM").andReturn());
        assertAuthenticationFailed(login("no-credential@example.test", "unused", "PLATFORM").andReturn());
        assertAuthenticationFailed(login("expired-initial@example.test", "initial-password", "PLATFORM").andReturn());
        for (int attempt = 0; attempt < 5; attempt++) {
            assertAuthenticationFailed(login("locked@example.test", "wrong", "PLATFORM").andReturn());
        }
        assertAuthenticationFailed(login("locked@example.test", "correct-password", "PLATFORM").andReturn());

        for (TestUser user : List.of(wrongPassword, noCredential, expiredInitial, locked)) {
            assertEquals(0, sessionFactCount(user.identity().id()));
        }
        assertEquals(1, redis.keys("sf:test:iam-service:login-lock:v1:*").size());
    }

    @Test
    @Order(3)
    void successfulPasswordClearsFailuresAndTenantDefaultCannotBorrowPlatformRole() throws Exception {
        TestUser user = createUser("default-tenant@example.test", "correct-password", true, Credential.REGULAR);
        for (int attempt = 0; attempt < 4; attempt++) {
            assertAuthenticationFailed(login("default-tenant@example.test", "wrong", "PLATFORM").andReturn());
        }

        MvcResult tenantResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"default-tenant@example.test","password":"correct-password"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();
        assertNotNull(tenantResponse);
        assertEquals(0, sessionFactCount(user.identity().id()));
        assertTrue(redis.keys("sf:test:iam-service:login-failure:v1:*").isEmpty());
        assertTrue(redis.keys("sf:test:iam-service:login-lock:v1:*").isEmpty());
    }

    @Test
    @Order(4)
    void platformRoleIsRequiredAfterPasswordVerification() throws Exception {
        TestUser user = createUser("no-role@example.test", "correct-password", false, Credential.REGULAR);
        login("no-role@example.test", "correct-password", "PLATFORM")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(5)
    void defaultTenantLoginWithOneMembershipIssuesPairedClaimsAndTenantSession() throws Exception {
        TestUser user = createUser("single-tenant@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
        UUID tenantId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
        accessibleMemberships(user.identity().id(), membership(membershipId, tenantId, "唯一租户"));

        MvcResult response = loginWithoutContext("single-tenant@example.test", "correct-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        JsonNode claims = tokenClaims(response);
        assertEquals(Set.of("iss", "aud", "iat", "exp", "identityId", "membershipId", "tenantId", "jti"),
                claims.propertyNames());
        assertEquals(membershipId.toString(), claims.get("membershipId").asString());
        assertEquals(tenantId.toString(), claims.get("tenantId").asString());
        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT family.family_purpose, family.membership_id, family.tenant_id,
                       issuance.membership_id AS issuance_membership_id,
                       issuance.tenant_id AS issuance_tenant_id
                FROM iam_refresh_token_families family
                JOIN iam_access_token_issuances issuance ON issuance.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals("USER_TENANT", facts.get("family_purpose"));
        assertEquals(membershipId, facts.get("membership_id"));
        assertEquals(tenantId, facts.get("tenant_id"));
        assertEquals(membershipId, facts.get("issuance_membership_id"));
        assertEquals(tenantId, facts.get("issuance_tenant_id"));
    }

    @Test
    @Order(6)
    void multipleMembershipsCreateOnlySelectionSessionAndReturnStableCandidates() throws Exception {
        TestUser user = createUser("multi-tenant@example.test", "correct-password", true, Credential.REGULAR);
        UUID alphaMembership = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca0");
        UUID betaMembership = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4ca1");
        accessibleMemberships(user.identity().id(),
                membership(betaMembership, UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4cb1"), "Beta"),
                membership(alphaMembership, UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4cb0"), "Alpha"));

        login("multi-tenant@example.test", "correct-password", "TENANT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("CONTEXT_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.memberships.length()").value(2))
                .andExpect(jsonPath("$.memberships[0].membershipId").value(alphaMembership.toString()))
                .andExpect(jsonPath("$.memberships[1].membershipId").value(betaMembership.toString()))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(header().exists("Set-Cookie"));

        assertEquals("USER_TENANT_SELECTION", jdbc.queryForObject(
                "SELECT family_purpose FROM iam_refresh_token_families WHERE identity_id = ?",
                String.class, user.identity().id()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));
        String event = jdbc.queryForObject(
                "SELECT event_snapshot::TEXT FROM iam_outbox_events WHERE ordering_key = ?",
                String.class, user.identity().id().toString());
        assertTrue(event.contains("\"purpose\": \"USER_TENANT_SELECTION\""));
        assertTrue(event.contains("\"contextType\": \"TENANT\""));
        assertTrue(event.contains("\"result\": \"CONTEXT_SELECTION_REQUIRED\""));
    }

    @Test
    @Order(7)
    void overOneHundredMembershipsRejectWithoutAuthenticationState() throws Exception {
        TestUser user = createUser("membership-overflow@example.test", "correct-password", false, Credential.REGULAR);
        List<io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership> memberships =
                java.util.stream.IntStream.range(0, 101)
                        .mapToObj(index -> membership(
                                uuidV7(10_000 + index), uuidV7(20_000 + index), "Tenant " + index))
                        .toList();
        ACCESSIBLE_MEMBERSHIPS.put(user.identity().id(), memberships);

        login("membership-overflow@example.test", "correct-password", "TENANT")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCESSIBLE_MEMBERSHIP_LIMIT_EXCEEDED"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(8)
    void tenantAccessFailureIsRetryableAndCreatesNoAuthenticationState() throws Exception {
        TestUser user = createUser("tenant-access-down@example.test", "correct-password", false, Credential.REGULAR);
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        login("tenant-access-down@example.test", "correct-password", "TENANT")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(9)
    void loginRequestRejectsCallerSuppliedTenantOrMembershipIdentifiers() throws Exception {
        TestUser user = createUser("forged-context@example.test", "correct-password", false, Credential.REGULAR);
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forged-context@example.test","password":"correct-password",
                                 "tenantId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc0"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"forged-context@example.test","password":"correct-password",
                                 "membershipId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertEquals(0, sessionFactCount(user.identity().id()));
    }

    @Test
    @Order(10)
    void contextSelectionRevalidatesMembershipThenAtomicallyRotatesIntoTenantSession() throws Exception {
        TestUser user = createUser("selection-success@example.test", "correct-password", false, Credential.REGULAR);
        UUID selectedMembership = uuidV7(30_001);
        UUID selectedTenant = uuidV7(30_002);
        UUID otherMembership = uuidV7(30_003);
        accessibleMemberships(user.identity().id(),
                membership(selectedMembership, selectedTenant, "Alpha"),
                membership(otherMembership, uuidV7(30_004), "Beta"));
        MvcResult pending = login("selection-success@example.test", "correct-password", "TENANT").andReturn();
        String selectionToken = refreshToken(pending);

        MvcResult selected = selectContext(selectionToken, selectedMembership)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        JsonNode claims = tokenClaims(selected);
        assertEquals(selectedMembership.toString(), claims.get("membershipId").asString());
        assertEquals(selectedTenant.toString(), claims.get("tenantId").asString());
        String rotatedToken = refreshToken(selected);
        assertFalse(selectionToken.equals(rotatedToken));
        Map<String, Object> family = jdbc.queryForMap("""
                SELECT family_purpose, membership_id, tenant_id, revoked_at
                FROM iam_refresh_token_families WHERE identity_id = ?
                """, user.identity().id());
        assertEquals("USER_TENANT", family.get("family_purpose"));
        assertEquals(selectedMembership, family.get("membership_id"));
        assertEquals(selectedTenant, family.get("tenant_id"));
        assertEquals(null, family.get("revoked_at"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, user.identity().id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NULL
                """, Integer.class, user.identity().id()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));

        selectContext(selectionToken, selectedMembership)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_SESSION_INVALID"));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));
    }

    @Test
    @Order(11)
    void inaccessibleSelectionIsRejectedAndConsumesTheSelectionSession() throws Exception {
        TestUser user = createUser("selection-rejected@example.test", "correct-password", false, Credential.REGULAR);
        UUID staleMembership = uuidV7(31_001);
        UUID currentMembership = uuidV7(31_002);
        accessibleMemberships(user.identity().id(),
                membership(staleMembership, uuidV7(31_003), "Alpha"),
                membership(currentMembership, uuidV7(31_004), "Beta"));
        String selectionToken = refreshToken(
                login("selection-rejected@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(user.identity().id(), membership(currentMembership, uuidV7(31_004), "Beta"));

        selectContext(selectionToken, staleMembership)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_REJECTED"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT family.revoked_at, token.consumed_at
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertNotNull(facts.get("revoked_at"));
        assertNotNull(facts.get("consumed_at"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_access_token_issuances issuance
                JOIN iam_refresh_token_families family ON family.id = issuance.family_id
                WHERE family.identity_id = ?
                """, Integer.class, user.identity().id()));
    }

    @Test
    @Order(12)
    void tenantAccessFailureDoesNotConsumeSelectionAndTheSameCookieCanRetry() throws Exception {
        TestUser user = createUser("selection-retry@example.test", "correct-password", false, Credential.REGULAR);
        UUID selectedMembership = uuidV7(32_001);
        UUID selectedTenant = uuidV7(32_002);
        accessibleMemberships(user.identity().id(),
                membership(selectedMembership, selectedTenant, "Alpha"),
                membership(uuidV7(32_003), uuidV7(32_004), "Beta"));
        String selectionToken = refreshToken(
                login("selection-retry@example.test", "correct-password", "TENANT").andReturn());
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        selectContext(selectionToken, selectedMembership)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        Map<String, Object> pendingFacts = jdbc.queryForMap("""
                SELECT family.family_purpose, family.revoked_at, token.consumed_at
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals("USER_TENANT_SELECTION", pendingFacts.get("family_purpose"));
        assertEquals(null, pendingFacts.get("revoked_at"));
        assertEquals(null, pendingFacts.get("consumed_at"));

        TENANT_ACCESS_FAILURES.remove(user.identity().id());
        selectContext(selectionToken, selectedMembership).andExpect(status().isOk());
    }

    @Test
    @Order(13)
    void expiredOrWrongPurposeCookieCannotCreateTenantSession() throws Exception {
        TestUser expired = createUser("selection-expired@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(33_001);
        accessibleMemberships(expired.identity().id(),
                membership(membershipId, uuidV7(33_002), "Alpha"),
                membership(uuidV7(33_003), uuidV7(33_004), "Beta"));
        String expiredToken = refreshToken(
                login("selection-expired@example.test", "correct-password", "TENANT").andReturn());
        jdbc.update("UPDATE iam_refresh_token_families SET last_used_at = now() - interval '31 minutes' WHERE identity_id = ?",
                expired.identity().id());
        selectContext(expiredToken, membershipId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_SESSION_INVALID"));

        TestUser tenant = createUser("selection-purpose@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(tenant.identity().id(), membership(membershipId, uuidV7(33_002), "Alpha"));
        String tenantToken = refreshToken(
                login("selection-purpose@example.test", "correct-password", "TENANT").andReturn());
        selectContext(tenantToken, membershipId)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONTEXT_SELECTION_SESSION_INVALID"));
        assertEquals("USER_TENANT", jdbc.queryForObject(
                "SELECT family_purpose FROM iam_refresh_token_families WHERE identity_id = ?",
                String.class, tenant.identity().id()));
    }

    @Test
    @Order(14)
    void contextSelectionBodyRejectsFieldsOtherThanMembershipId() throws Exception {
        mockMvc.perform(post("/api/v1/auth/context-selections")
                        .header("X-SF-CSRF", "csrf-test")
                        .cookie(new Cookie("__Host-sf_refresh", "not-a-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"membershipId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc1",
                                 "tenantId":"0198c9d5-0f25-7b21-8d67-31c8652d4cc2"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(15)
    void initialCredentialTakesPriorityAndPasswordChangeCommitsAllFactsAtomically() throws Exception {
        TestUser user = createUser(
                "initial-change@example.test", "Initial-Credential-2026!", true, Credential.ACTIVE_INITIAL);
        TENANT_ACCESS_FAILURES.add(user.identity().id());

        MvcResult login = login("initial-change@example.test", "Initial-Credential-2026!", "TENANT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.memberships").doesNotExist())
                .andReturn();
        String setCookie = login.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("Max-Age=600"));
        String refreshToken = refreshToken(login);

        Map<String, Object> initialFacts = jdbc.queryForMap("""
                SELECT family.family_purpose, family.initial_credential_id, family.absolute_expires_at,
                       token.consumed_at, family.revoked_at
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals("INITIAL_PASSWORD_CHANGE", initialFacts.get("family_purpose"));
        assertNotNull(initialFacts.get("initial_credential_id"));
        assertEquals(null, initialFacts.get("consumed_at"));
        assertEquals(null, initialFacts.get("revoked_at"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_access_token_issuances WHERE identity_id = ?",
                Integer.class, user.identity().id()));

        changePassword(refreshToken, "saasforge2026")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_COMPROMISED"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM iam_credentials WHERE identity_id = ? AND credential_type = 'PASSWORD'",
                Integer.class, user.identity().id()));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, user.identity().id()));

        changePassword(refreshToken, "Unique-Credential-2026!")
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        Map<String, Object> completed = jdbc.queryForMap("""
                SELECT family.revoked_at, token.consumed_at,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.identity_id = family.identity_id
                          AND credential.credential_type = 'PASSWORD' AND credential.invalidated_at IS NULL) AS regular_count,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.id = family.initial_credential_id
                          AND credential.invalidated_at IS NOT NULL) AS invalidated_initial_count
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertNotNull(completed.get("revoked_at"));
        assertNotNull(completed.get("consumed_at"));
        assertEquals(1L, completed.get("regular_count"));
        assertEquals(1L, completed.get("invalidated_initial_count"));

        JsonNode event = json(jdbc.queryForObject("""
                SELECT event_snapshot::TEXT FROM iam_outbox_events
                WHERE ordering_key = ? AND event_snapshot ->> 'type' = 'com.saasforge.iam.password.changed.v1'
                """, String.class, user.identity().id().toString()).getBytes(StandardCharsets.UTF_8));
        assertEquals("com.saasforge.iam.password.changed.v1", event.get("type").asString());
        assertFalse(event.toString().contains("Unique-Credential-2026!"));

        assertAuthenticationFailed(login("initial-change@example.test", "Initial-Credential-2026!", "PLATFORM")
                .andReturn());
        login("initial-change@example.test", "Unique-Credential-2026!", "PLATFORM")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"));
    }

    @Test
    @Order(16)
    void passwordChangePublicContractCoversPasswordBoundariesWithoutConsumingSession() throws Exception {
        TestUser user = createUser(
                "password-boundaries@example.test", "Initial-Boundaries-2026!", false, Credential.ACTIVE_INITIAL);
        String refreshToken = refreshToken(login(
                "password-boundaries@example.test", "Initial-Boundaries-2026!", "TENANT").andReturn());

        changePassword(refreshToken, "12345678901")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_SHORT"));
        changePassword(refreshToken, "12345678901\u00a0x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_WHITESPACE_NOT_ALLOWED"));
        changePassword(refreshToken, "x".repeat(129))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));

        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, user.identity().id()));
    }

    @Test
    @Order(17)
    void outboxFailureRollsBackCredentialAndRestrictedSessionChanges() throws Exception {
        TestUser user = createUser(
                "password-rollback@example.test", "Initial-Rollback-2026!", false, Credential.ACTIVE_INITIAL);
        String refreshToken = refreshToken(login(
                "password-rollback@example.test", "Initial-Rollback-2026!", "TENANT").andReturn());

        assertThrows(IllegalArgumentException.class, () -> passwordChangeService.change(
                refreshToken, "Unique-Rollback-2026!", "invalid-trace-id"));

        Map<String, Object> facts = jdbc.queryForMap("""
                SELECT family.revoked_at, token.consumed_at,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.identity_id = family.identity_id
                          AND credential.credential_type = 'PASSWORD') AS regular_count,
                       (SELECT count(*) FROM iam_credentials credential
                        WHERE credential.id = family.initial_credential_id
                          AND credential.invalidated_at IS NOT NULL) AS invalidated_initial_count
                FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE family.identity_id = ?
                """, user.identity().id());
        assertEquals(null, facts.get("revoked_at"));
        assertEquals(null, facts.get("consumed_at"));
        assertEquals(0L, facts.get("regular_count"));
        assertEquals(0L, facts.get("invalidated_initial_count"));
    }

    @Test
    @Order(18)
    void refreshRevalidatesEveryUserPurposeAndRotatesCurrentSessionFacts() throws Exception {
        TestUser platform = createUser("refresh-platform@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult platformLogin = login("refresh-platform@example.test", "correct-password", "PLATFORM").andReturn();
        String platformToken = refreshToken(platformLogin);
        String platformJti = tokenClaims(platformLogin).get("jti").asString();
        jdbc.update("""
                UPDATE iam_refresh_token_families
                SET absolute_expires_at = now() + interval '5 minutes'
                WHERE identity_id = ?
                """, platform.identity().id());

        MvcResult platformRefresh = refresh(platformToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=")))
                .andReturn();
        String rotatedPlatformToken = refreshToken(platformRefresh);
        String refreshedJti = tokenClaims(platformRefresh).get("jti").asString();
        assertFalse(platformToken.equals(rotatedPlatformToken));
        assertFalse(platformJti.equals(refreshedJti));
        assertEquals(7, UUID.fromString(refreshedJti).version());
        int maxAge = Integer.parseInt(Pattern.compile("Max-Age=(\\d+)")
                .matcher(platformRefresh.getResponse().getHeader("Set-Cookie")).results()
                .findFirst().orElseThrow().group(1));
        assertTrue(maxAge > 0 && maxAge <= 300);
        assertEquals(1, consumedRefreshTokenCount(platform.identity().id()));
        assertEquals(1, activeRefreshTokenCount(platform.identity().id()));
        assertEquals(2, accessTokenIssuanceCount(platform.identity().id()));

        TestUser tenant = createUser("refresh-tenant@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(40_001);
        UUID tenantId = uuidV7(40_002);
        accessibleMemberships(tenant.identity().id(), membership(membershipId, tenantId, "Tenant"));
        String tenantToken = refreshToken(
                login("refresh-tenant@example.test", "correct-password", "TENANT").andReturn());
        MvcResult tenantRefresh = refresh(tenantToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andReturn();
        assertEquals(membershipId.toString(), tokenClaims(tenantRefresh).get("membershipId").asString());
        assertEquals(tenantId.toString(), tokenClaims(tenantRefresh).get("tenantId").asString());
        assertEquals(2, accessTokenIssuanceCount(tenant.identity().id()));

        TestUser selection = createUser(
                "refresh-selection@example.test", "correct-password", false, Credential.REGULAR);
        UUID firstMembership = uuidV7(41_001);
        UUID secondMembership = uuidV7(41_002);
        accessibleMemberships(selection.identity().id(),
                membership(firstMembership, uuidV7(41_003), "Alpha"),
                membership(secondMembership, uuidV7(41_004), "Beta"));
        String selectionToken = refreshToken(
                login("refresh-selection@example.test", "correct-password", "TENANT").andReturn());
        UUID replacementMembership = uuidV7(41_005);
        accessibleMemberships(selection.identity().id(),
                membership(secondMembership, uuidV7(41_004), "Beta"),
                membership(replacementMembership, uuidV7(41_006), "Gamma"));

        MvcResult selectionRefresh = refresh(selectionToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("CONTEXT_SELECTION_REQUIRED"))
                .andExpect(jsonPath("$.memberships[0].membershipId").value(secondMembership.toString()))
                .andExpect(jsonPath("$.memberships[1].membershipId").value(replacementMembership.toString()))
                .andReturn();
        assertFalse(selectionToken.equals(refreshToken(selectionRefresh)));
        assertEquals(1, consumedRefreshTokenCount(selection.identity().id()));
        assertEquals(1, activeRefreshTokenCount(selection.identity().id()));
        assertEquals(0, accessTokenIssuanceCount(selection.identity().id()));

        TestUser narrowedSelection = createUser(
                "refresh-selection-narrowed@example.test", "correct-password", false, Credential.REGULAR);
        UUID narrowedMembership = uuidV7(41_007);
        UUID narrowedTenant = uuidV7(41_008);
        accessibleMemberships(narrowedSelection.identity().id(),
                membership(narrowedMembership, narrowedTenant, "Alpha"),
                membership(uuidV7(41_009), uuidV7(41_010), "Beta"));
        String narrowedToken = refreshToken(
                login("refresh-selection-narrowed@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(narrowedSelection.identity().id(),
                membership(narrowedMembership, narrowedTenant, "Alpha"));
        MvcResult narrowedRefresh = refresh(narrowedToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contextState").value("ACCESS_TOKEN_ISSUED"))
                .andReturn();
        assertEquals(narrowedMembership.toString(), tokenClaims(narrowedRefresh).get("membershipId").asString());
        assertEquals("USER_TENANT", jdbc.queryForObject(
                "SELECT family_purpose FROM iam_refresh_token_families WHERE identity_id = ?",
                String.class, narrowedSelection.identity().id()));
        assertEquals(1, accessTokenIssuanceCount(narrowedSelection.identity().id()));
    }

    @Test
    @Order(19)
    void refreshFailuresPreserveRetryableSessionsAndRevokeExplicitAuthorizationLoss() throws Exception {
        TestUser retryable = createUser(
                "refresh-retryable@example.test", "correct-password", false, Credential.REGULAR);
        UUID membershipId = uuidV7(42_001);
        UUID tenantId = uuidV7(42_002);
        accessibleMemberships(retryable.identity().id(), membership(membershipId, tenantId, "Tenant"));
        String retryableToken = refreshToken(
                login("refresh-retryable@example.test", "correct-password", "TENANT").andReturn());
        TENANT_ACCESS_FAILURES.add(retryable.identity().id());
        refresh(retryableToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TENANT_ACCESS_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertSessionUnchanged(retryable.identity().id(), 1);
        TENANT_ACCESS_FAILURES.remove(retryable.identity().id());
        refresh(retryableToken).andExpect(status().isOk());

        TestUser tenantLoss = createUser(
                "refresh-tenant-loss@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(tenantLoss.identity().id(), membership(membershipId, tenantId, "Tenant"));
        String tenantLossToken = refreshToken(
                login("refresh-tenant-loss@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(tenantLoss.identity().id());
        refresh(tenantLossToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertEquals(1, consumedRefreshTokenCount(tenantLoss.identity().id()));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, tenantLoss.identity().id()));
        assertEquals(1, accessTokenIssuanceCount(tenantLoss.identity().id()));

        TestUser platformLoss = createUser(
                "refresh-platform-loss@example.test", "correct-password", true, Credential.REGULAR);
        String platformLossToken = refreshToken(
                login("refresh-platform-loss@example.test", "correct-password", "PLATFORM").andReturn());
        jdbc.update("UPDATE iam_platform_role_assignments SET revoked_at = now() WHERE identity_id = ?",
                platformLoss.identity().id());
        refresh(platformLossToken)
                .andExpect(status().isForbidden())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));

        TestUser selectionLoss = createUser(
                "refresh-selection-loss@example.test", "correct-password", false, Credential.REGULAR);
        accessibleMemberships(selectionLoss.identity().id(),
                membership(uuidV7(43_001), uuidV7(43_002), "Alpha"),
                membership(uuidV7(43_003), uuidV7(43_004), "Beta"));
        String selectionLossToken = refreshToken(
                login("refresh-selection-loss@example.test", "correct-password", "TENANT").andReturn());
        accessibleMemberships(selectionLoss.identity().id());
        refresh(selectionLossToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_CONTEXT_UNAVAILABLE"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertEquals(1, consumedRefreshTokenCount(selectionLoss.identity().id()));
        assertEquals(0, accessTokenIssuanceCount(selectionLoss.identity().id()));

        TestUser initial = createUser(
                "refresh-initial@example.test", "Initial-Refresh-2026!", false, Credential.ACTIVE_INITIAL);
        String initialToken = refreshToken(
                login("refresh-initial@example.test", "Initial-Refresh-2026!", "TENANT").andReturn());
        refresh(initialToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_SESSION_INVALID"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        assertSessionUnchanged(initial.identity().id(), 0);

        TestUser signing = createUser(
                "refresh-signing@example.test", "correct-password", true, Credential.REGULAR);
        String signingToken = refreshToken(
                login("refresh-signing@example.test", "correct-password", "PLATFORM").andReturn());
        int outboxCount = jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE ordering_key = ?",
                Integer.class, signing.identity().id().toString());
        SIGNING_FAILURE.set(true);
        refresh(signingToken)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("TOKEN_SIGNING_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertSessionUnchanged(signing.identity().id(), 1);
        assertEquals(outboxCount, jdbc.queryForObject(
                "SELECT count(*) FROM iam_outbox_events WHERE ordering_key = ?",
                Integer.class, signing.identity().id().toString()));
        SIGNING_FAILURE.set(false);
        refresh(signingToken).andExpect(status().isOk());
    }

    @Test
    @Order(20)
    void logoutWithoutCookieOrIdempotencyKeyIsStillSuccessfulAndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("X-SF-CSRF", "csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("__Host-sf_refresh="),
                        org.hamcrest.Matchers.containsString("Max-Age=0"),
                        org.hamcrest.Matchers.containsString("Secure"),
                        org.hamcrest.Matchers.containsString("HttpOnly"),
                        org.hamcrest.Matchers.containsString("SameSite=Strict"),
                        org.hamcrest.Matchers.containsString("Path=/"))));
    }

    @Test
    @Order(21)
    void logoutRevokesOnlyPresentedFamilyAndBearerJtiAndIsIdempotent() throws Exception {
        TestUser user = createUser("logout-isolation@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult currentSession = login("logout-isolation@example.test", "correct-password", "PLATFORM").andReturn();
        MvcResult otherSession = login("logout-isolation@example.test", "correct-password", "PLATFORM").andReturn();
        String currentRefreshToken = refreshToken(currentSession);
        String currentAccessToken = accessToken(currentSession);
        UUID currentJti = UUID.fromString(tokenClaims(currentSession).get("jti").asString());

        logout(currentRefreshToken, currentAccessToken)
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        logout(currentRefreshToken, currentAccessToken).andExpect(status().isNoContent());

        byte[] currentDigest = MessageDigest.getInstance("SHA-256")
                .digest(currentRefreshToken.getBytes(StandardCharsets.UTF_8));
        assertNotNull(jdbc.queryForObject("""
                SELECT family.revoked_at FROM iam_refresh_token_families family
                JOIN iam_refresh_tokens token ON token.family_id = family.id
                WHERE token.token_digest = ?
                """, Object.class, currentDigest));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_token_families
                WHERE identity_id = ? AND revoked_at IS NULL
                """, Integer.class, user.identity().id()));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, currentJti));

        String jtiDigest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(currentJti.toString().getBytes(StandardCharsets.UTF_8)));
        String revocationKey = "sf:test:iam-service:jwt-jti-revocation:v1:" + jtiDigest;
        assertEquals("1", redis.opsForValue().get(revocationKey));
        assertTrue(redis.getExpire(revocationKey) > 0);
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saasforge.iam.session.revoked.v1'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));

        refresh(refreshToken(otherSession)).andExpect(status().isOk());
    }

    @Test
    @Order(22)
    void postgresFailureAfterRedisWriteRollsBackFamilyAndOutboxButKeepsExtraRejection() throws Exception {
        TestUser user = createUser("logout-postgres-failure@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult session = login("logout-postgres-failure@example.test", "correct-password", "PLATFORM").andReturn();
        String refreshToken = refreshToken(session);
        String accessToken = accessToken(session);
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());

        setIamAppIssuanceUpdatePrivilege(false);
        try {
            logout(refreshToken, accessToken)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("LOGOUT_UNAVAILABLE"))
                    .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        } finally {
            setIamAppIssuanceUpdatePrivilege(true);
        }

        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saasforge.iam.session.revoked.v1'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(jti)));
    }

    @Test
    @Order(23)
    void refreshRecoversSameKeyOnceThenTreatsSecondRetryAsFamilyReplay() throws Exception {
        TestUser user = createUser("refresh-recovery@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult login = login("refresh-recovery@example.test", "correct-password", "PLATFORM").andReturn();
        String oldRefreshToken = refreshToken(login);
        UUID originalJti = UUID.fromString(tokenClaims(login).get("jti").asString());
        UUID key = uuidV7(50_001);

        MvcResult first = refresh(oldRefreshToken, key)
                .andExpect(status().isOk())
                .andReturn();
        String firstSuccessor = refreshToken(first);
        UUID firstJti = UUID.fromString(tokenClaims(first).get("jti").asString());

        MvcResult recovered = refresh(oldRefreshToken, key)
                .andExpect(status().isOk())
                .andReturn();
        String replacement = refreshToken(recovered);
        UUID replacementJti = UUID.fromString(tokenClaims(recovered).get("jti").asString());
        assertFalse(firstSuccessor.equals(replacement));
        assertFalse(firstJti.equals(replacementJti));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, firstJti));
        assertEquals("1", redis.opsForValue().get(jtiRevocationKey(firstJti)));
        assertEquals(2, consumedRefreshTokenCount(user.identity().id()));
        assertEquals(1, activeRefreshTokenCount(user.identity().id()));

        refresh(oldRefreshToken, key)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_SESSION_INVALID"));

        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        for (UUID jti : List.of(originalJti, firstJti, replacementJti)) {
            assertNotNull(jdbc.queryForObject(
                    "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
            assertEquals("1", redis.opsForValue().get(jtiRevocationKey(jti)));
        }
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saasforge.iam.refresh-replay-detected.v1'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));
        assertEquals(1, jdbc.queryForObject("""
                SELECT count(*) FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' = 'com.saasforge.iam.session.revoked.v1'
                  AND event_snapshot::jsonb #>> '{data,result}' = 'CURRENT_SESSION_REVOKED'
                  AND ordering_key = ?
                """, Integer.class, user.identity().id().toString()));
    }

    @Test
    @Order(24)
    void differentKeyConflictsDuringLeaseAndBecomesReplayAfterLeaseEnds() throws Exception {
        TestUser user = createUser("refresh-lease@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult login = login("refresh-lease@example.test", "correct-password", "PLATFORM").andReturn();
        String oldRefreshToken = refreshToken(login);
        UUID firstKey = uuidV7(51_001);
        UUID secondKey = uuidV7(51_002);
        refresh(oldRefreshToken, firstKey).andExpect(status().isOk());
        int issuanceCount = accessTokenIssuanceCount(user.identity().id());

        refresh(oldRefreshToken, secondKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFRESH_ROTATION_IN_PROGRESS"))
                .andExpect(header().exists("Retry-After"));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        assertEquals(issuanceCount, accessTokenIssuanceCount(user.identity().id()));

        redis.delete(refreshRotationLeaseKey(oldRefreshToken));
        refresh(oldRefreshToken, secondKey)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("REFRESH_SESSION_INVALID"));
        assertNotNull(jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
    }

    @Test
    @Order(25)
    void concurrentRefreshesRecoverSameKeyAndRejectDifferentKeyWithoutPartialState() throws Exception {
        TestUser sameKeyUser = createUser(
                "refresh-concurrent-same@example.test", "correct-password", true, Credential.REGULAR);
        String sameKeyToken = refreshToken(
                login("refresh-concurrent-same@example.test", "correct-password", "PLATFORM").andReturn());
        UUID sameKey = uuidV7(53_001);
        List<MvcResult> sameKeyResults = concurrentRefreshes(
                sameKeyToken, sameKey, sameKey);
        assertEquals(List.of(200, 200), sameKeyResults.stream()
                .map(result -> result.getResponse().getStatus()).sorted().toList());
        assertEquals(2, consumedRefreshTokenCount(sameKeyUser.identity().id()));
        assertEquals(1, activeRefreshTokenCount(sameKeyUser.identity().id()));
        assertEquals(3, accessTokenIssuanceCount(sameKeyUser.identity().id()));

        TestUser differentKeyUser = createUser(
                "refresh-concurrent-different@example.test", "correct-password", true, Credential.REGULAR);
        String differentKeyToken = refreshToken(
                login("refresh-concurrent-different@example.test", "correct-password", "PLATFORM").andReturn());
        List<MvcResult> differentKeyResults = concurrentRefreshes(
                differentKeyToken, uuidV7(53_002), uuidV7(53_003));
        assertEquals(List.of(200, 409), differentKeyResults.stream()
                .map(result -> result.getResponse().getStatus()).sorted().toList());
        MvcResult conflict = differentKeyResults.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .findFirst().orElseThrow();
        assertEquals("REFRESH_ROTATION_IN_PROGRESS",
                json(conflict.getResponse().getContentAsByteArray()).get("code").asString());
        assertNotNull(conflict.getResponse().getHeader("Retry-After"));
        assertEquals(1, consumedRefreshTokenCount(differentKeyUser.identity().id()));
        assertEquals(1, activeRefreshTokenCount(differentKeyUser.identity().id()));
        assertEquals(2, accessTokenIssuanceCount(differentKeyUser.identity().id()));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, differentKeyUser.identity().id()));
    }

    @Test
    @Order(26)
    void clientCredentialsEndpointRejectsWrongSecretRevokedClientAndOverprivilegedScope() throws Exception {
        Instant now = Instant.now().minusSeconds(1);
        UUID clientId = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
        String secret = serviceClientSecret((byte) 11);
        oauthClients.createWithId(
                OAuthClient.register("iam-service", Set.of(OAuthScope.TENANT_ACCESS_MEMBERSHIP_READ), now)
                        .identifiedBy(clientId),
                ClientSecretDigest.fromPlaintext(secret), now);

        MvcResult issued = mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials")
                        .param("scope", "tenant-access:membership:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(300))
                .andExpect(jsonPath("$.scope").value("tenant-access:membership:read"))
                .andReturn();
        String token = json(issued.getResponse().getContentAsByteArray()).get("access_token").asString();
        JsonNode header = json(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
        JsonNode claims = json(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertEquals("at+jwt", header.get("typ").asString());
        assertEquals(clientId.toString(), claims.get("client_id").asString());
        assertEquals(Set.of("iss", "aud", "iat", "exp", "jti", "sub", "client_id", "scope"),
                claims.propertyNames());

        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, serviceClientSecret((byte) 12)))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials")
                        .param("scope", "iam:identity:write"))
                .andExpect(status().isForbidden());

        oauthClients.revoke(clientId, Instant.now());
        mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(29)
    void recoveryFailsClosedUntilDurableJtiAndKidRevocationsAreRebuilt() throws Exception {
        TestUser user = createUser("revocation-recovery@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult session = login("revocation-recovery@example.test", "correct-password", "PLATFORM").andReturn();
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());
        UUID activeKeyId = jdbc.queryForObject(
                "SELECT id FROM iam_signing_keys WHERE kid = 'active-login-kid'", UUID.class);
        UUID replacementKeyId = jdbc.queryForObject("""
                INSERT INTO iam_signing_keys
                    (kid, key_version_reference, public_jwk_modulus, public_jwk_exponent,
                     key_status, published_at)
                VALUES ('emergency-replacement-kid', 'fake/key/emergency', 'replacement-modulus', 'AQAB',
                        'PUBLISHED', now() - interval '10 minutes')
                RETURNING id
                """, UUID.class);
        try {
            signingKeyLifecycleService.emergencyRevoke(activeKeyId, replacementKeyId);
            assertEquals("REVOKED", jdbc.queryForObject(
                    "SELECT key_status FROM iam_signing_keys WHERE id = ?", String.class, activeKeyId));
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "SELECT key_status FROM iam_signing_keys WHERE id = ?", String.class, replacementKeyId));
            assertEquals("SIGNING_KEY_COMPROMISED", jdbc.queryForObject(
                    "SELECT revocation_reason FROM iam_access_token_issuances WHERE jti = ?", String.class, jti));
            Set<String> revocationKeys = redis.keys("sf:test:iam-service:*revocation*:v1:*");
            if (revocationKeys != null && !revocationKeys.isEmpty()) {
                redis.delete(revocationKeys);
            }
            revocationIndex.markNotReady();
            assertThrows(RevocationIndexUnavailableException.class,
                    () -> revocationIndex.isTokenRevoked(jti, "active-login-kid"));

            revocationIndexRecovery.recover();

            assertTrue(revocationIndex.isJtiRevoked(jti));
            assertTrue(revocationIndex.isKidRevoked("active-login-kid"));
            assertTrue(revocationIndex.isTokenRevoked(jti, "active-login-kid"));
            String kidDigest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest("active-login-kid".getBytes(StandardCharsets.UTF_8)));
            assertEquals("1", redis.opsForValue().get(
                    "sf:test:iam-service:signing-kid-revocation:v1:" + kidDigest));
            assertEquals("1", redis.opsForValue().get(
                    "sf:test:iam-service:revocation-index-ready:v1:state"));
        } finally {
            jdbc.update("""
                    UPDATE iam_signing_keys
                    SET key_status = 'PUBLISHED', activated_at = NULL
                    WHERE id = ?
                    """, replacementKeyId);
            jdbc.update("""
                    UPDATE iam_signing_keys
                    SET key_status = 'ACTIVE', revoked_at = NULL
                    WHERE kid = 'active-login-kid'
                    """);
            try (Connection connection = DriverManager.getConnection(
                    iamJdbcUrl(), "iam_migrator", "iam-migrator-password")) {
                try (var statement = connection.prepareStatement(
                        "DELETE FROM iam_signing_keys WHERE id = ?")) {
                    statement.setObject(1, replacementKeyId);
                    statement.executeUpdate();
                }
            }
        }
    }

    @Test
    @Order(32)
    void redisUnavailabilityFailsClosedThroughPublicContract() throws Exception {
        TestUser user = createUser("logout-redis-down@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult session = login("logout-redis-down@example.test", "correct-password", "PLATFORM").andReturn();
        TestUser refreshUser = createUser(
                "refresh-redis-down@example.test", "correct-password", true, Credential.REGULAR);
        MvcResult refreshSession = login(
                "refresh-redis-down@example.test", "correct-password", "PLATFORM").andReturn();
        UUID jti = UUID.fromString(tokenClaims(session).get("jti").asString());
        REDIS.stop();
        refresh(refreshToken(refreshSession), uuidV7(52_001))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFRESH_ROTATION_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertSessionUnchanged(refreshUser.identity().id(), 1);
        logout(refreshToken(session), accessToken(session))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REVOCATION_INDEX_UNAVAILABLE"));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, user.identity().id()));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_access_token_issuances WHERE jti = ?", Object.class, jti));
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-SF-CSRF", "csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"redis-down@example.test","password":"password","contextType":"PLATFORM"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_PROTECTION_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @Order(31)
    void anonymousPasswordSetupAcceptsOnlySecretBodyAndCreatesNoSession() throws Exception {
        TestUser user = createUser("password-setup-http@example.test", "unused", false, Credential.NONE);
        PasswordSetupChallengeToken challenge = passwordSetupService.issueChallenge(user.identity().id());

        mockMvc.perform(post("/api/v1/auth/password-setups")
                        .header("Idempotency-Key", uuidV7(53_001).toString())
                        .header("X-SF-CSRF", "1")
                        .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "token", challenge.value(),
                                "newPassword", "HTTP-Setup-Password-2026"))))
                .andExpect(status().isNoContent())
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertEquals(0, sessionFactCount(user.identity().id()));
        assertEquals(1, identities.findCredentials(user.identity().id()).size());

        TestUser rejected = createUser("password-setup-extra-field@example.test", "unused", false, Credential.NONE);
        PasswordSetupChallengeToken rejectedChallenge = passwordSetupService.issueChallenge(rejected.identity().id());
        mockMvc.perform(post("/api/v1/auth/password-setups")
                        .header("Idempotency-Key", uuidV7(53_002).toString())
                        .header("X-SF-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsBytes(Map.of(
                                "token", rejectedChallenge.value(),
                                "newPassword", "HTTP-Setup-Password-2026",
                                "email", "forged@example.test"))))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("Set-Cookie"));
        assertTrue(identities.findCredentials(rejected.identity().id()).isEmpty());

        mockMvc.perform(post("/api/v1/auth/password-setups")
                        .header("Idempotency-Key", uuidV7(53_003).toString())
                        .header("X-SF-CSRF", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
                                + "\"newPassword\":\"HTTP-Setup-Password-2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_SETUP_TOKEN_INVALID"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private org.springframework.test.web.servlet.ResultActions selectContext(String refreshToken, UUID membershipId)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/context-selections")
                .header("X-SF-CSRF", "csrf-test")
                .cookie(new Cookie("__Host-sf_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("membershipId", membershipId))));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return refresh(refreshToken, UUID.fromString(IDEMPOTENCY_KEY));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken, UUID idempotencyKey)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .header("Idempotency-Key", idempotencyKey.toString())
                .header("X-SF-CSRF", "csrf-test")
                .cookie(new Cookie("__Host-sf_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private List<MvcResult> concurrentRefreshes(String refreshToken, UUID firstKey, UUID secondKey) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> concurrentRefresh(refreshToken, firstKey, ready, start));
            var second = executor.submit(() -> concurrentRefresh(refreshToken, secondKey, ready, start));
            ready.await();
            start.countDown();
            return List.of(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private MvcResult concurrentRefresh(
            String refreshToken, UUID idempotencyKey, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return refresh(refreshToken, idempotencyKey).andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions logout(String refreshToken, String accessToken)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/logout")
                .header("X-SF-CSRF", "csrf-test")
                .header("Authorization", "Bearer " + accessToken)
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .cookie(new Cookie("__Host-sf_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(String refreshToken, String newPassword)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-changes")
                .header("X-SF-CSRF", "csrf-test")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .cookie(new Cookie("__Host-sf_refresh", refreshToken))
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("newPassword", newPassword))));
    }

    private static String refreshToken(MvcResult response) {
        String setCookie = response.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        Matcher matcher = COOKIE_VALUE.matcher(setCookie);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password, String contextType)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-SF-CSRF", "csrf-test")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of(
                        "email", email, "password", password, "contextType", contextType))));
    }

    private org.springframework.test.web.servlet.ResultActions loginWithoutContext(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("X-SF-CSRF", "csrf-test")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of("email", email, "password", password))));
    }

    private static JsonNode tokenClaims(MvcResult response) {
        return json(Base64.getUrlDecoder().decode(accessToken(response).split("\\.")[1]));
    }

    private static String accessToken(MvcResult response) {
        return json(response.getResponse().getContentAsByteArray()).get("accessToken").asString();
    }

    private static String jtiRevocationKey(UUID jti) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(jti.toString().getBytes(StandardCharsets.UTF_8)));
        return "sf:test:iam-service:jwt-jti-revocation:v1:" + digest;
    }

    private static String refreshRotationLeaseKey(String refreshToken) throws Exception {
        String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        return "sf:test:iam-service:refresh-rotation-lease:v1:" + digest;
    }

    private static void setIamAppIssuanceUpdatePrivilege(boolean granted) throws Exception {
        String sql = (granted ? "GRANT" : "REVOKE")
                + " UPDATE ON TABLE iam_access_token_issuances "
                + (granted ? "TO" : "FROM") + " iam_app";
        try (Connection connection = DriverManager.getConnection(
                iamJdbcUrl(), "iam_migrator", "iam-migrator-password")) {
            connection.createStatement().execute(sql);
        }
    }

    private static String basic(UUID clientId, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String serviceClientSecret(byte value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void accessibleMemberships(
            UUID identityId,
            io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership... memberships) {
        ACCESSIBLE_MEMBERSHIPS.put(identityId, List.of(memberships));
    }

    private static io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership membership(
            UUID membershipId, UUID tenantId, String displayName) {
        return io.saasforge.contracts.tenantaccess.membership.v1.AccessibleMembership.newBuilder()
                .setMembershipId(membershipId.toString())
                .setTenantId(tenantId.toString())
                .setTenantDisplayName(displayName)
                .build();
    }

    private static UUID uuidV7(long suffix) {
        return UUID.fromString(String.format("0198c9d5-0f25-7000-8000-%012x", suffix));
    }

    private void assertAuthenticationFailed(MvcResult result) {
        assertEquals(401, result.getResponse().getStatus());
        JsonNode problem = json(result.getResponse().getContentAsByteArray());
        assertEquals("AUTHENTICATION_FAILED", problem.get("code").asString());
        assertEquals("邮箱或密码无效", problem.get("detail").asString());
        assertEquals(null, result.getResponse().getHeader("Set-Cookie"));
    }

    private TestUser createUser(String email, String password, boolean withRole, Credential credential) {
        Instant now = Instant.now();
        Identity identity = identities.create(Identity.register(email, null, now));
        if (credential == Credential.REGULAR) {
            identities.create(PasswordCredential.regular(identity.id(), passwordHash(password), now));
        } else if (credential == Credential.ACTIVE_INITIAL) {
            identities.create(PasswordCredential.initial(identity.id(), passwordHash(password), now));
        } else if (credential == Credential.EXPIRED_INITIAL) {
            identities.create(PasswordCredential.initial(
                    identity.id(), passwordHash(password), now.minus(Duration.ofHours(25))));
        }
        if (withRole) {
            platformRoles.grant(PlatformRoleAssignment.grant(
                    identity.id(), "PLATFORM_ADMIN", now.minusSeconds(1)));
        }
        return new TestUser(identity);
    }

    private int sessionFactCount(UUID identityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM iam_refresh_token_families WHERE identity_id = ?", Integer.class, identityId);
    }

    private int consumedRefreshTokenCount(UUID identityId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NOT NULL
                """, Integer.class, identityId);
    }

    private int activeRefreshTokenCount(UUID identityId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM iam_refresh_tokens token
                JOIN iam_refresh_token_families family ON family.id = token.family_id
                WHERE family.identity_id = ? AND token.consumed_at IS NULL
                """, Integer.class, identityId);
    }

    private int accessTokenIssuanceCount(UUID identityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM iam_access_token_issuances WHERE identity_id = ?", Integer.class, identityId);
    }

    private void assertSessionUnchanged(UUID identityId, int expectedIssuances) {
        assertEquals(0, consumedRefreshTokenCount(identityId));
        assertEquals(1, activeRefreshTokenCount(identityId));
        assertEquals(expectedIssuances, accessTokenIssuanceCount(identityId));
        assertEquals(null, jdbc.queryForObject(
                "SELECT revoked_at FROM iam_refresh_token_families WHERE identity_id = ?",
                Object.class, identityId));
    }

    private static Argon2idPasswordHash passwordHash(String password) {
        return Argon2idPasswordHash.of(new Argon2PasswordEncoder(16, 32, 1, 19_456, 2).encode(password));
    }

    private static ConsumerRecord<String, String> awaitEvent(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("未收到 session.started 事件");
    }

    private static KafkaConsumer<String, String> kafkaConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "iam-login-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }

    private static JsonNode json(byte[] value) {
        return new ObjectMapper().readTree(value);
    }

    private static void migrateAndSeedSigningKey() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")) {
            connection.createStatement().execute("""
                    INSERT INTO iam_signing_keys
                        (kid, key_version_reference, public_jwk_modulus, public_jwk_exponent,
                         key_status, published_at, activated_at)
                    VALUES ('active-login-kid', 'fake/key/1', 'test-modulus', 'AQAB',
                            'ACTIVE', now() - interval '10 minutes', now() - interval '5 minutes')
                    """);
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备 IAM 登录集成测试", exception);
        }
    }

    private static String iamJdbcUrl() {
        return POSTGRES.getJdbcUrl().replace("/saasforge", "/iam_db");
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("deploy/postgresql/bootstrap.sh"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    enum Credential { REGULAR, ACTIVE_INITIAL, EXPIRED_INITIAL, NONE }

    record TestUser(Identity identity) { }

    static final class TenantAccessAuthority extends AccessibleMembershipQueryServiceGrpc.AccessibleMembershipQueryServiceImplBase {
        @Override
        public void listAccessibleMemberships(
                ListAccessibleMembershipsRequest request,
                StreamObserver<ListAccessibleMembershipsResponse> responseObserver) {
            UUID identityId = UUID.fromString(request.getIdentityId());
            if (TENANT_ACCESS_FAILURES.contains(identityId)) {
                responseObserver.onError(Status.UNAVAILABLE.asRuntimeException());
                return;
            }
            responseObserver.onNext(ListAccessibleMembershipsResponse.newBuilder()
                    .addAllMemberships(ACCESSIBLE_MEMBERSHIPS.getOrDefault(identityId, List.of()))
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableScheduling
    @EnableTransactionManagement
    @MapperScan(basePackages = "io.saasforge.iam.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "iamSqlSessionFactory")
    @ComponentScan(basePackageClasses = {
            MyBatisIdentityRepository.class,
            AuthenticationController.class,
            OutboxPublisher.class
    })
    @Import(AuthenticationConfiguration.class)
    static class TestConfiguration {
        @Bean
        static ConversionService conversionService() {
            return ApplicationConversionService.getSharedInstance();
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(iamJdbcUrl(), "iam_app", "iam-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory iamSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saasforge.iam.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory iamSqlSessionFactory) {
            return new SqlSessionTemplate(iamSqlSessionFactory);
        }

        @Bean
        LettuceConnectionFactory redisConnectionFactory() {
            RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                    REDIS.getHost(), REDIS.getMappedPort(6379));
            configuration.setPassword(RedisPassword.of(REDIS_PASSWORD));
            return new LettuceConnectionFactory(configuration);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory redisConnectionFactory) {
            return new StringRedisTemplate(redisConnectionFactory);
        }

        @Bean
        KafkaTemplate<String, String> kafkaTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                    org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    org.apache.kafka.clients.producer.ProducerConfig.ACKS_CONFIG, "all",
                    org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }

        @Bean
        JsonMapper objectMapper() {
            return JsonMapper.builder()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
        }

        @Bean
        WebMvcConfigurer strictJsonWebMvc(JsonMapper objectMapper) {
            return new WebMvcConfigurer() {
                @Override
                public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
                    converters.removeIf(JacksonJsonHttpMessageConverter.class::isInstance);
                    converters.add(0, new JacksonJsonHttpMessageConverter(objectMapper));
                }
            };
        }

        @Bean
        AccessibleMemberships accessibleMemberships() {
            return new GrpcAccessibleMemberships(
                    AccessibleMembershipQueryServiceGrpc.newBlockingStub(TENANT_ACCESS_CHANNEL));
        }

        @Bean
        JwtSigningPort jwtSigningPort() {
            return (keyReference, algorithm, signingInput) -> {
                try {
                    if (SIGNING_FAILURE.get()) {
                        throw new IllegalStateException("injected signing failure");
                    }
                    return MessageDigest.getInstance("SHA-256").digest(signingInput.bytes());
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            };
        }

        @Bean
        JwtSigningService jwtSigningService(SigningKeyRepository repository, JwtSigningPort signingPort) {
            return new JwtSigningService(new ActiveSigningKeyResolver(repository), signingPort);
        }

        @Bean
        SigningKeyRevocationTransaction signingKeyRevocationTransaction(
                SigningKeyRepository signingKeys, AccessTokenIssuanceRepository issuances) {
            return new SigningKeyRevocationTransaction(signingKeys, issuances);
        }

        @Bean
        SigningKeyLifecycleService signingKeyLifecycleService(
                SigningKeyRepository signingKeys,
                AccessTokenIssuanceRepository issuances,
                RevocationIndex revocationIndex,
                SigningKeyRevocationTransaction transaction,
                java.time.Clock clock) {
            return new SigningKeyLifecycleService(signingKeys, issuances, revocationIndex, transaction, clock);
        }

        @Bean
        PresentedAccessTokenVerifier presentedAccessTokenVerifier() {
            return authorization -> {
                if (authorization == null || !authorization.startsWith("Bearer ")) {
                    return Optional.empty();
                }
                try {
                    String[] segments = authorization.substring(7).split("\\.");
                    JsonNode header = json(Base64.getUrlDecoder().decode(segments[0]));
                    JsonNode claims = json(Base64.getUrlDecoder().decode(segments[1]));
                    return Optional.of(new PresentedAccessToken(
                            UUID.fromString(claims.get("jti").asString()),
                            header.get("kid").asString(),
                            Instant.ofEpochSecond(claims.get("exp").asLong())));
                } catch (RuntimeException invalidToken) {
                    return Optional.empty();
                }
            };
        }

    }
}
