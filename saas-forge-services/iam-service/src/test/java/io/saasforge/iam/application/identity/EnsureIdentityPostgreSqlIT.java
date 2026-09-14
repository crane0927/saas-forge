package io.saasforge.iam.application.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.application.bootstrap.ReservedServiceClient;
import io.saasforge.iam.domain.client.ClientSecretDigest;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.identity.IdentityCredentialStatus;
import io.saasforge.iam.domain.identity.IdentityProvisioningRepository;
import io.saasforge.iam.domain.identity.IdentityRepository;
import io.saasforge.iam.infrastructure.persistence.MyBatisIdentityRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.springframework.dao.DataIntegrityViolationException;
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

@Testcontainers
@SpringJUnitConfig(EnsureIdentityPostgreSqlIT.TestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnsureIdentityPostgreSqlIT {
    private static final Instant NOW = Instant.parse("2026-08-22T05:00:00Z");
    private static final UUID TENANT_ACCESS_CLIENT_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c8f");
    private static final UUID MISSING_CLIENT_ID =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c90");
    private static final UUID REQUEST_A =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c91");
    private static final UUID REQUEST_B =
            UUID.fromString("0198c9d5-0f25-7b21-8d67-31c8652d4c92");
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
    private EnsureIdentityService service;

    @Autowired
    private OAuthClientRepository clients;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    void migrateAndCreateCaller() {
        Flyway.configure()
                .dataSource(iamJdbcUrl(), "iam_migrator", "iam-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
        OAuthClient tenantAccess = OAuthClient.register(
                        ReservedServiceClient.TENANT_ACCESS.displayName(),
                        ReservedServiceClient.TENANT_ACCESS.allowedScopes(),
                        NOW)
                .identifiedBy(TENANT_ACCESS_CLIENT_ID);
        clients.createWithId(tenantAccess, ClientSecretDigest.fromPlaintext(secret()), NOW);
    }

    @Test
    void deduplicatesConcurrentEmailReplaysStableResultAndRollsBackIdentityWithoutFact() throws Exception {
        assertThrows(DataIntegrityViolationException.class,
                () -> service.ensure(MISSING_CLIENT_ID, REQUEST_A, "rollback@example.test", null));
        assertEquals(0, countWhere("iam_identities", "normalized_email = 'rollback@example.test'"));
        assertEquals(0, count("iam_identity_provisioning_facts"));

        EnsureIdentityResult first;
        EnsureIdentityResult second;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(() -> service.ensure(
                    TENANT_ACCESS_CLIENT_ID, REQUEST_A, " Admin@Example.Test ", "First Name"));
            var secondFuture = executor.submit(() -> service.ensure(
                    TENANT_ACCESS_CLIENT_ID, REQUEST_B, "admin@example.test", "Second Name"));
            first = firstFuture.get();
            second = secondFuture.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(first.identityId(), second.identityId());
        assertEquals(IdentityCredentialStatus.SETUP_ALLOWED, first.credentialStatus());
        assertEquals(IdentityCredentialStatus.SETUP_ALLOWED, second.credentialStatus());
        assertEquals(1, countWhere("iam_identities", "normalized_email = 'admin@example.test'"));
        assertEquals(2, count("iam_identity_provisioning_facts"));

        EnsureIdentityResult replay = service.ensure(
                TENANT_ACCESS_CLIENT_ID, REQUEST_A, "admin@example.test", "First Name");
        assertEquals(first, replay);
        assertEquals(2, count("iam_identity_provisioning_facts"));
        assertThrows(EnsureIdentityRequestConflictException.class,
                () -> service.ensure(TENANT_ACCESS_CLIENT_ID, REQUEST_A, "other@example.test", "First Name"));
        assertEquals(1, countWhere("iam_identities", "normalized_email = 'admin@example.test'"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int countWhere(String table, String condition) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + condition, Integer.class);
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) 7);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
        EnsureIdentityService ensureIdentityService(
                IdentityProvisioningRepository requests,
                IdentityRepository identities,
                Clock clock) {
            return new EnsureIdentityService(requests, identities, clock);
        }
    }
}
