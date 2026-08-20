package io.saasforge.iam.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import io.saasforge.iam.application.signing.JwtSigningPort;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.config.AuthenticationConfiguration;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.identity.Argon2idPasswordHash;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.infrastructure.messaging.OutboxPublisher;
import io.saasforge.iam.infrastructure.persistence.MyBatisIdentityRepository;
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
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringJUnitConfig(AuthenticationHttpIT.TestConfiguration.class)
@TestPropertySource(properties = {
        "saasforge.environment=test",
        "security.jwt.issuer=https://iam.test.saasforge.invalid",
        "security.jwt.access-token-ttl=PT15M",
        "security.login-protection.failure-window=PT15M",
        "security.login-protection.maximum-failures=5",
        "security.login-protection.lock-duration=PT15M",
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
            .withCommand("redis-server", "--appendonly", "yes", "--requirepass", REDIS_PASSWORD)
            .withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    static {
        POSTGRES.start();
        REDIS.start();
        KAFKA.start();
        migrateAndSeedSigningKey();
    }

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    IdentityRepository identities;

    @Autowired
    PlatformRoleAssignmentRepository platformRoles;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    DataSource dataSource;

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
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
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
    void redisUnavailabilityFailsClosedThroughPublicContract() throws Exception {
        REDIS.stop();
        mockMvc.perform(post("/api/v1/auth/login")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .header("X-SF-CSRF", "csrf-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"redis-down@example.test","password":"password","contextType":"PLATFORM"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_PROTECTION_UNAVAILABLE"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password, String contextType)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                .header("X-SF-CSRF", "csrf-test")
                .header("traceparent", "00-" + TRACE_ID + "-0123456789abcdef-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content(new ObjectMapper().writeValueAsBytes(Map.of(
                        "email", email, "password", password, "contextType", contextType))));
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
        } else if (credential == Credential.EXPIRED_INITIAL) {
            identities.create(PasswordCredential.initial(
                    identity.id(), passwordHash(password), now.minus(Duration.ofHours(25))));
        }
        if (withRole) {
            platformRoles.grant(PlatformRoleAssignment.grant(identity.id(), "PLATFORM_ADMIN", now));
        }
        return new TestUser(identity);
    }

    private int sessionFactCount(UUID identityId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM iam_refresh_token_families WHERE identity_id = ?", Integer.class, identityId);
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

    enum Credential { REGULAR, EXPIRED_INITIAL, NONE }

    record TestUser(Identity identity) { }

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
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        JwtSigningPort jwtSigningPort() {
            return (keyReference, algorithm, signingInput) -> {
                try {
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

    }
}
