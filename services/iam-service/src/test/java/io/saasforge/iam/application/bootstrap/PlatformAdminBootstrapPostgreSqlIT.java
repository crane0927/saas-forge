package io.saasforge.iam.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.iam.application.authentication.PasswordPolicy;
import io.saasforge.iam.application.authentication.PasswordVerifier;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.bootstrap.PlatformAdminBootstrapRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
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
@SpringJUnitConfig(PlatformAdminBootstrapPostgreSqlIT.TestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformAdminBootstrapPostgreSqlIT {
    private static final Instant NOW = Instant.parse("2026-08-21T01:02:03Z");
    private static final String EMAIL = "platform-admin@example.test";
    private static final String PASSWORD = "Random-Initial-Password-2026";
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
    private PlatformAdminBootstrapService service;

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
    void commitsAllFactsIdempotentlyAndRollsBackEveryPartialWrite() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> service.bootstrap(EMAIL, PASSWORD, "invalid-trace"));
        assertCounts(0);

        PlatformAdminBootstrapResult first;
        PlatformAdminBootstrapResult second;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(() -> service.bootstrap(EMAIL, PASSWORD, TRACE_ID));
            var secondFuture = executor.submit(() -> service.bootstrap(EMAIL, PASSWORD, TRACE_ID));
            first = firstFuture.get();
            second = secondFuture.get();
        } finally {
            executor.shutdownNow();
        }
        assertEquals(Set.of(
                        PlatformAdminBootstrapResult.Outcome.INITIALIZED,
                        PlatformAdminBootstrapResult.Outcome.ALREADY_INITIALIZED),
                Set.of(first.outcome(), second.outcome()));
        PlatformAdminBootstrapResult initialized = first.outcome()
                == PlatformAdminBootstrapResult.Outcome.INITIALIZED ? first : second;
        assertEquals(NOW.plusSeconds(24 * 60 * 60), initialized.credentialExpiresAt());
        assertCounts(1);

        String passwordHash = jdbc.queryForObject("SELECT password_hash FROM iam_credentials", String.class);
        assertTrue(passwordHash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"));
        assertFalse(passwordHash.contains(PASSWORD));
        String event = jdbc.queryForObject(
                "SELECT event_snapshot::TEXT FROM iam_outbox_events", String.class);
        assertTrue(event.contains("com.saasforge.iam.platform-admin-initialized.v1"));
        assertTrue(event.contains(TRACE_ID));
        assertFalse(event.contains(EMAIL));
        assertFalse(event.contains(PASSWORD));
        assertFalse(event.contains("argon2id"));

        PlatformAdminBootstrapResult replayed = service.bootstrap(" PLATFORM-ADMIN@EXAMPLE.TEST ", PASSWORD, TRACE_ID);
        assertEquals(PlatformAdminBootstrapResult.Outcome.ALREADY_INITIALIZED, replayed.outcome());
        assertEquals(initialized.identityId(), replayed.identityId());
        assertCounts(1);

        assertThrows(PlatformAdminBootstrapConflictException.class,
                () -> service.bootstrap(EMAIL, "Different-Random-Password-2026", TRACE_ID));
        assertCounts(1);

        assertEquals(1, jdbc.update(
                "UPDATE iam_platform_role_assignments SET revoked_at = ? WHERE id = ?",
                java.time.OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), initialized.roleAssignmentId()));
        assertThrows(PlatformAdminBootstrapConflictException.class,
                () -> service.bootstrap(EMAIL, PASSWORD, TRACE_ID));
        assertCounts(1);
    }

    private void assertCounts(int expected) {
        assertEquals(expected, count("iam_identities"));
        assertEquals(expected, count("iam_credentials"));
        assertEquals(expected, count("iam_platform_role_assignments"));
        assertEquals(expected, count("iam_platform_admin_bootstrap_facts"));
        assertEquals(expected, count("iam_outbox_events"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
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
        PlatformAdminInitializedEventFactory eventFactory(Clock clock) {
            return new PlatformAdminInitializedEventFactory(
                    new ObjectMapper(), new UuidV7Generator(clock, new SecureRandom()), "test");
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
    }
}
