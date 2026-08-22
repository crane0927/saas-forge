package io.saasforge.iam.application.authentication;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordSetupChallengeRepository;
import io.saasforge.iam.domain.outbox.ClaimedOutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEvent;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringJUnitConfig(PasswordSetupPostgreSqlIT.TestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordSetupPostgreSqlIT {
    private static final Instant NOW = Instant.parse("2026-08-22T06:30:00Z");
    private static final UUID KEY_A = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e01");
    private static final UUID KEY_B = UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4e02");
    private static final Path REPOSITORY_ROOT = repositoryRoot();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"))
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

    static {
        POSTGRES.start();
    }

    @Autowired private PasswordSetupService service;
    @Autowired @Qualifier("failingPasswordSetupService") private PasswordSetupService failingService;
    @Autowired private IdentityRepository identities;
    @Autowired private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void storesOnlyDigestAndAtomicallyReplacesTheOpenChallenge() throws Exception {
        UUID identityId = createIdentity("replace@example.test");
        PasswordSetupChallengeToken first = service.issueChallenge(identityId);
        PasswordSetupChallengeToken second = service.issueChallenge(identityId);

        assertEquals(43, first.value().length());
        assertEquals(43, second.value().length());
        assertFalse(first.value().equals(second.value()));
        byte[] storedDigest = jdbc.queryForObject(
                "SELECT token_digest FROM iam_password_setup_challenges "
                        + "WHERE identity_id = ? AND invalidated_at IS NULL",
                byte[].class, identityId);
        assertArrayEquals(MessageDigest.getInstance("SHA-256")
                .digest(second.value().getBytes(StandardCharsets.UTF_8)), storedDigest);
        assertEquals(1, countWhere("iam_password_setup_challenges",
                "identity_id = '" + identityId + "' AND invalidated_at IS NOT NULL"));
        String rowText = jdbc.queryForObject(
                "SELECT row_to_json(challenge)::TEXT FROM iam_password_setup_challenges challenge "
                        + "WHERE identity_id = ? AND invalidated_at IS NULL", String.class, identityId);
        assertFalse(rowText.contains(first.value()));
        assertFalse(rowText.contains(second.value()));
    }

    @Test
    void atomicallyCreatesCredentialConsumesChallengeStoresReplayAndSafeOutbox() {
        UUID identityId = createIdentity("success@example.test");
        PasswordSetupChallengeToken issued = service.issueChallenge(identityId);
        String password = "Safe-Password-For-Setup-2026";

        service.establishPassword(KEY_A, issued.value(), password, "4bf92f3577b34da6a3ce929d0e0e4736");
        service.establishPassword(KEY_A, issued.value(), "Different-Retry-Password-2026", null);

        assertEquals(1, countWhere("iam_credentials", "identity_id = '" + identityId + "'"));
        assertEquals(1, countWhere("iam_password_setup_challenges",
                "identity_id = '" + identityId + "' AND consumed_at IS NOT NULL"));
        assertEquals(KEY_A, jdbc.queryForObject(
                "SELECT idempotency_key FROM iam_password_setup_challenges WHERE identity_id = ?",
                UUID.class, identityId));
        assertEquals(204, jdbc.queryForObject(
                "SELECT completed_status FROM iam_password_setup_challenges WHERE identity_id = ?",
                Integer.class, identityId));
        String outbox = jdbc.queryForObject(
                "SELECT event_snapshot::TEXT FROM iam_outbox_events WHERE ordering_key = ?",
                String.class, identityId.toString());
        assertTrue(outbox.contains(PasswordEstablishedEventFactory.EVENT_TYPE));
        assertFalse(outbox.contains(password));
        assertFalse(outbox.contains(issued.value()));
        assertEquals(0, countWhere("iam_refresh_token_families", "identity_id = '" + identityId + "'"));
        assertThrows(PasswordSetupTokenInvalidException.class,
                () -> service.establishPassword(KEY_B, issued.value(), password, null));
    }

    @Test
    void concurrentDifferentKeysAllowOneSuccessAndUniformlyRejectTheOther() throws Exception {
        UUID identityId = createIdentity("concurrent@example.test");
        PasswordSetupChallengeToken issued = service.issueChallenge(identityId);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> establish(KEY_A, issued.value()));
            var second = executor.submit(() -> establish(KEY_B, issued.value()));
            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertEquals(1, successes);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, countWhere("iam_credentials", "identity_id = '" + identityId + "'"));
        assertEquals(1, countWhere("iam_outbox_events", "ordering_key = '" + identityId + "'"));
    }

    @Test
    void rollsBackCredentialChallengeAndOutboxFactWhenOutboxAppendFails() {
        UUID identityId = createIdentity("rollback-setup@example.test");
        PasswordSetupChallengeToken issued = failingService.issueChallenge(identityId);

        assertThrows(IllegalStateException.class,
                () -> failingService.establishPassword(KEY_A, issued.value(), "Rollback-Password-2026", null));

        assertEquals(0, countWhere("iam_credentials", "identity_id = '" + identityId + "'"));
        assertEquals(0, countWhere("iam_password_setup_challenges",
                "identity_id = '" + identityId + "' AND consumed_at IS NOT NULL"));
        assertEquals(0, countWhere("iam_outbox_events", "ordering_key = '" + identityId + "'"));
    }

    private boolean establish(UUID key, String token) {
        try {
            service.establishPassword(key, token, "Concurrent-Password-2026", null);
            return true;
        } catch (PasswordSetupTokenInvalidException exception) {
            return false;
        }
    }

    private UUID createIdentity(String email) {
        return identities.create(Identity.register(email, null, NOW)).id();
    }

    private int countWhere(String table, String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + condition, Integer.class);
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

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @MapperScan(
            basePackages = "io.saasforge.iam.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "iamSqlSessionFactory")
    @ComponentScan(basePackageClasses = MyBatisIdentityRepository.class)
    static class TestConfiguration {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource(iamJdbcUrl(), "iam_app", "iam-app-password");
        }
        @Bean PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean SqlSessionFactory iamSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saasforge.iam.infrastructure.persistence.type");
            return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource dataSource) { return new JdbcTemplate(dataSource); }
        @Bean Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
        @Bean SecureRandom secureRandom() { return new SecureRandom(); }
        @Bean PasswordSetupChallengeIssuer issuer(SecureRandom random) {
            return new PasswordSetupChallengeIssuer(random);
        }
        @Bean PasswordPolicy passwordPolicy() { return new PasswordPolicy(); }
        @Bean PasswordVerifier passwordVerifier() { return new PasswordVerifier(); }
        @Bean CompromisedPasswordChecker compromisedPasswordChecker() { return password -> false; }
        @Bean PasswordEstablishedEventFactory eventFactory(Clock clock, SecureRandom random) {
            return new PasswordEstablishedEventFactory(
                    new ObjectMapper(), new UuidV7Generator(clock, random), "test");
        }
        @Bean
        @Primary
        PasswordSetupService passwordSetupService(
                PasswordSetupChallengeRepository challenges,
                IdentityRepository identities,
                PasswordSetupChallengeIssuer issuer,
                PasswordPolicy policy,
                CompromisedPasswordChecker compromised,
                PasswordVerifier verifier,
                OutboxEventRepository outbox,
                PasswordEstablishedEventFactory eventFactory,
                Clock clock) {
            return new PasswordSetupService(
                    challenges, identities, issuer, policy, compromised, verifier, outbox, eventFactory, clock);
        }
        @Bean("failingPasswordSetupService")
        PasswordSetupService failingPasswordSetupService(
                PasswordSetupChallengeRepository challenges,
                IdentityRepository identities,
                PasswordSetupChallengeIssuer issuer,
                PasswordPolicy policy,
                CompromisedPasswordChecker compromised,
                PasswordVerifier verifier,
                PasswordEstablishedEventFactory eventFactory,
                Clock clock) {
            OutboxEventRepository failingOutbox = new OutboxEventRepository() {
                @Override public void append(OutboxEvent event) { throw new IllegalStateException("outbox failed"); }
                @Override public Optional<ClaimedOutboxEvent> claimNext(String claimant, Instant at, Instant until) {
                    throw new UnsupportedOperationException();
                }
                @Override public void markPublished(ClaimedOutboxEvent event, Instant at) {
                    throw new UnsupportedOperationException();
                }
                @Override public void releaseAfterFailure(ClaimedOutboxEvent event, Instant at, String summary) {
                    throw new UnsupportedOperationException();
                }
            };
            return new PasswordSetupService(
                    challenges, identities, issuer, policy, compromised, verifier, failingOutbox, eventFactory, clock);
        }
    }
}
