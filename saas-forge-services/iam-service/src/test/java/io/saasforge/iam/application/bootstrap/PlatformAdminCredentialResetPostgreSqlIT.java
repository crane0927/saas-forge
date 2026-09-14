package io.saasforge.iam.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminCredentialResetRepository;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.identity.PasswordCredential;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.session.RefreshTokenFamily;
import io.saasforge.iam.domain.session.RefreshTokenFamilyRepository;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
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
@SpringJUnitConfig(PlatformAdminCredentialResetPostgreSqlIT.TestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformAdminCredentialResetPostgreSqlIT {
    private static final Instant NOW = Instant.parse("2026-08-21T03:00:00Z");
    private static final String EMAIL = "platform-admin-reset@example.test";
    private static final String INITIAL_PASSWORD = "Original-Initial-Password-2026";
    private static final String RESET_PASSWORD = "Reset-Initial-Password-2026";
    private static final String TRACE_ID = "1234567890abcdef1234567890abcdef";
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

    @Autowired
    private PlatformAdminBootstrapService bootstrapService;

    @Autowired
    private PlatformAdminCredentialResetService resetService;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private RefreshTokenFamilyRepository families;

    @Autowired
    private PasswordVerifier passwordVerifier;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void resetsAtomicallyReplaysAndRejectsEstablishedPasswordWithoutTouchingOtherIdentity() throws Exception {
        PlatformAdminBootstrapResult initialized = bootstrapService.bootstrap(
                EMAIL, INITIAL_PASSWORD, TRACE_ID);
        RefreshTokenFamily initialFamily = families.create(
                RefreshTokenFamily.startInitialPasswordChange(
                        initialized.identityId(), initialized.credentialId(), NOW, initialized.credentialExpiresAt()),
                digest((byte) 1), NOW);

        Identity other = identities.create(Identity.register("other@example.test", null, NOW));
        PasswordCredential otherCredential = identities.create(PasswordCredential.initial(
                other.id(), passwordVerifier.hash("Other-Initial-Password-2026"), NOW));
        RefreshTokenFamily otherFamily = families.create(
                RefreshTokenFamily.startInitialPasswordChange(
                        other.id(), otherCredential.id(), NOW, otherCredential.expiresAt()),
                digest((byte) 2), NOW);

        UUID rollbackRequestId = uuidV7();
        assertThrows(IllegalArgumentException.class,
                () -> resetService.reset(rollbackRequestId, RESET_PASSWORD, "invalid-trace"));
        assertEquals(0, count("iam_platform_admin_credential_reset_facts"));
        assertEquals(2, count("iam_credentials"));
        assertNull(families.findById(initialFamily.id()).orElseThrow().revokedAt());
        assertNull(identities.findCredential(initialized.credentialId()).orElseThrow().invalidatedAt());

        UUID requestId = uuidV7();
        PlatformAdminCredentialResetResult first;
        PlatformAdminCredentialResetResult second;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(() -> resetService.reset(requestId, RESET_PASSWORD, TRACE_ID));
            var secondFuture = executor.submit(() -> resetService.reset(requestId, RESET_PASSWORD, TRACE_ID));
            first = firstFuture.get();
            second = secondFuture.get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(Set.of(
                        PlatformAdminCredentialResetResult.Outcome.RESET,
                        PlatformAdminCredentialResetResult.Outcome.ALREADY_RESET),
                Set.of(first.outcome(), second.outcome()));
        PlatformAdminCredentialResetResult reset = first.outcome()
                == PlatformAdminCredentialResetResult.Outcome.RESET ? first : second;
        assertEquals(reset.credentialId(), second.outcome()
                == PlatformAdminCredentialResetResult.Outcome.ALREADY_RESET
                ? second.credentialId() : first.credentialId());
        assertEquals(NOW.plusSeconds(24 * 60 * 60), reset.credentialExpiresAt());
        assertNotNull(families.findById(initialFamily.id()).orElseThrow().revokedAt());
        assertNotNull(identities.findCredential(initialized.credentialId()).orElseThrow().invalidatedAt());
        assertNull(families.findById(otherFamily.id()).orElseThrow().revokedAt());
        assertNull(identities.findCredential(otherCredential.id()).orElseThrow().invalidatedAt());
        assertEquals(1, count("iam_platform_admin_credential_reset_facts"));

        String event = jdbc.queryForObject("""
                SELECT event_snapshot::TEXT
                FROM iam_outbox_events
                WHERE event_snapshot::jsonb ->> 'type' =
                      'com.saasforge.iam.platform-admin-initial-credential-reset.v1'
                """, String.class);
        assertTrue(event.contains(requestId.toString()));
        assertFalse(event.contains(EMAIL));
        assertFalse(event.contains(RESET_PASSWORD));
        assertFalse(event.contains("argon2id"));

        UUID nextRequestId = uuidV7();
        PlatformAdminCredentialResetResult nextReset = resetService.reset(
                nextRequestId, "Next-Reset-Initial-Password-2026", TRACE_ID);
        assertEquals(PlatformAdminCredentialResetResult.Outcome.RESET, nextReset.outcome());
        assertFalse(nextReset.credentialId().equals(reset.credentialId()));
        assertNotNull(identities.findCredential(reset.credentialId()).orElseThrow().invalidatedAt());
        assertEquals(2, count("iam_platform_admin_credential_reset_facts"));

        PasswordCredential established = identities.create(PasswordCredential.regular(
                initialized.identityId(), passwordVerifier.hash("Established-Password-2026"), NOW));
        int credentialCount = count("iam_credentials");
        int outboxCount = count("iam_outbox_events");
        assertThrows(PlatformAdminCredentialResetConflictException.class,
                () -> resetService.reset(uuidV7(), "Another-Reset-Password-2026", TRACE_ID));
        assertEquals(credentialCount, count("iam_credentials"));
        assertEquals(outboxCount, count("iam_outbox_events"));
        assertEquals(2, count("iam_platform_admin_credential_reset_facts"));
        assertNull(identities.findCredential(established.id()).orElseThrow().invalidatedAt());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private static Sha256Digest digest(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Sha256Digest.of(bytes);
    }

    private static UUID uuidV7() {
        long random = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(0x0000000000007000L, (random & 0x3fffffffffffffffL) | 0x8000000000000000L);
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
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @Bean
        PasswordPolicy passwordPolicy() {
            return new PasswordPolicy();
        }

        @Bean
        PasswordVerifier passwordVerifier() {
            return new PasswordVerifier();
        }

        @Bean
        UuidV7Generator uuidV7Generator(Clock clock) {
            return new UuidV7Generator(clock, new SecureRandom());
        }

        @Bean
        PlatformAdminInitializedEventFactory initializedEventFactory(UuidV7Generator uuidV7Generator) {
            return new PlatformAdminInitializedEventFactory(new ObjectMapper(), uuidV7Generator, "test");
        }

        @Bean
        PlatformAdminCredentialResetEventFactory resetEventFactory(UuidV7Generator uuidV7Generator) {
            return new PlatformAdminCredentialResetEventFactory(new ObjectMapper(), uuidV7Generator, "test");
        }

        @Bean
        PlatformAdminBootstrapService platformAdminBootstrapService(
                IdentityRepository identities,
                PlatformRoleAssignmentRepository roles,
                PlatformAdminBootstrapRepository facts,
                OutboxEventRepository outbox,
                PlatformAdminInitializedEventFactory eventFactory,
                PasswordPolicy passwordPolicy,
                PasswordVerifier passwordVerifier,
                Clock clock) {
            return new PlatformAdminBootstrapService(
                    identities, roles, facts, outbox, eventFactory, passwordPolicy, passwordVerifier, clock);
        }

        @Bean
        PlatformAdminCredentialResetService platformAdminCredentialResetService(
                PlatformAdminBootstrapRepository bootstrapFacts,
                PlatformAdminCredentialResetRepository resetFacts,
                IdentityRepository identities,
                RefreshTokenFamilyRepository families,
                OutboxEventRepository outbox,
                PlatformAdminCredentialResetEventFactory eventFactory,
                PasswordPolicy passwordPolicy,
                PasswordVerifier passwordVerifier,
                Clock clock) {
            return new PlatformAdminCredentialResetService(
                    bootstrapFacts, resetFacts, identities, families, outbox, eventFactory,
                    passwordPolicy, passwordVerifier, clock);
        }
    }
}
