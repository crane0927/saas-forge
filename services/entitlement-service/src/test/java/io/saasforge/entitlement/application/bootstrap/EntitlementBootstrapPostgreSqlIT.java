package io.saasforge.entitlement.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.entitlement.domain.plan.PlanTransitionException;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisEntitlementBootstrapIdempotency;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisEntitlementOutboxEventRepository;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisPlanRepository;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisQuotaDefinitionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringJUnitConfig(EntitlementBootstrapPostgreSqlIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntitlementBootstrapPostgreSqlIT {
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
                    MountableFile.forHostPath(REPOSITORY_ROOT.resolve("deploy/postgresql/bootstrap.sh")),
                    "/docker-entrypoint-initdb.d/01-bootstrap.sh");

    static {
        POSTGRES.start();
    }

    @Autowired
    private EntitlementBootstrapService service;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), "entitlement_migrator", "entitlement-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws SQLException {
        executeAsMigrator("TRUNCATE entitlement_outbox_events, entitlement_bootstrap_idempotency, "
                + "plan_quotas, plans, quota_definitions CASCADE");
    }

    @AfterAll
    void stop() {
        POSTGRES.stop();
    }

    @Test
    void commitsFourStableOperationsAndAllowlistedOutboxEvents() throws SQLException {
        UUID actor = uuidV7(1);
        QuotaDefinitionResult quota = service.createQuotaDefinition(actor, uuidV7(2), "max_users",
                "11111111111111111111111111111111");
        assertEquals(quota, service.createQuotaDefinition(actor, uuidV7(2), "max_users",
                "11111111111111111111111111111111"));
        service.activateQuotaDefinition(actor, uuidV7(3), quota.id(), null);
        PlanResult plan = service.createPlan(actor, uuidV7(4), "starter", "Starter", quota.id(), 10, null);
        assertEquals(plan, service.createPlan(actor, uuidV7(4), "starter", "Starter", quota.id(), 10, null));
        service.activatePlan(actor, uuidV7(5), plan.id(), null);

        assertEquals(1, count("quota_definitions"));
        assertEquals(1, count("plans"));
        assertEquals(1, count("plan_quotas"));
        assertEquals(4, count("entitlement_bootstrap_idempotency"));
        assertEquals(4, count("entitlement_outbox_events"));
        String snapshots = scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events");
        assertTrue(snapshots.contains("com.saasforge.quota-definition.created.v1"));
        assertTrue(snapshots.contains("com.saasforge.quota-definition.activated.v1"));
        assertTrue(snapshots.contains("com.saasforge.plan.created.v1"));
        assertTrue(snapshots.contains("com.saasforge.plan.activated.v1"));
        assertFalse(snapshots.contains("displayName"));
        assertFalse(snapshots.contains("quotaLimit"));
    }

    @Test
    void databaseAndStateMachinesRejectDuplicatesAndRepeatedActivation() throws SQLException {
        UUID actor = uuidV7(10);
        QuotaDefinitionResult quota = service.createQuotaDefinition(actor, uuidV7(11), "max_users", null);
        assertThrows(io.saasforge.entitlement.domain.quota.QuotaDefinitionAlreadyExistsException.class,
                () -> service.createQuotaDefinition(actor, uuidV7(12), "max_users", null));
        service.activateQuotaDefinition(actor, uuidV7(13), quota.id(), null);
        assertThrows(QuotaDefinitionTransitionException.class,
                () -> service.activateQuotaDefinition(actor, uuidV7(14), quota.id(), null));

        PlanResult plan = service.createPlan(actor, uuidV7(15), "starter", "Starter", quota.id(), 1, null);
        assertThrows(io.saasforge.entitlement.domain.plan.PlanAlreadyExistsException.class,
                () -> service.createPlan(actor, uuidV7(16), "starter", "Other", quota.id(), 2, null));
        service.activatePlan(actor, uuidV7(17), plan.id(), null);
        assertThrows(PlanTransitionException.class,
                () -> service.activatePlan(actor, uuidV7(18), plan.id(), null));

        assertEquals(1, count("quota_definitions"));
        assertEquals(1, count("plans"));
        assertEquals(4, count("entitlement_outbox_events"));
    }

    @Test
    void concurrentSameCallerAndKeyCreatesExactlyOneDefinition() throws Exception {
        UUID actor = uuidV7(20);
        UUID key = uuidV7(21);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<QuotaDefinitionResult> first = executor.submit(() -> {
                start.await();
                return service.createQuotaDefinition(actor, key, "max_users", null);
            });
            Future<QuotaDefinitionResult> second = executor.submit(() -> {
                start.await();
                return service.createQuotaDefinition(actor, key, "max_users", null);
            });
            start.countDown();
            assertEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("quota_definitions"));
        assertEquals(1, count("entitlement_outbox_events"));
    }

    @Test
    void outboxFailureRollsBackDomainStateAndIdempotency() throws SQLException {
        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_entitlement_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$;
                CREATE TRIGGER fail_entitlement_outbox BEFORE INSERT ON entitlement_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_entitlement_outbox()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> service.createQuotaDefinition(uuidV7(30), uuidV7(31), "max_users", null));
            assertEquals(0, count("quota_definitions"));
            assertEquals(0, count("entitlement_bootstrap_idempotency"));
            assertEquals(0, count("entitlement_outbox_events"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_entitlement_outbox ON entitlement_outbox_events; "
                    + "DROP FUNCTION fail_entitlement_outbox()");
        }
    }

    @Test
    void platformGlobalTablesDoNotFabricateTenantScopeAndRuntimeRoleCannotBypassRls() throws SQLException {
        assertEquals(0, Integer.parseInt(scalar("""
                SELECT count(*)::text
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('quota_definitions', 'plans', 'plan_quotas')
                  AND column_name = 'tenant_id'
                """)));
        assertFalse(Boolean.parseBoolean(scalar(
                "SELECT rolbypassrls::text FROM pg_roles WHERE rolname = 'entitlement_app'")));
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.execute("SET ROLE entitlement_migrator"));
        }
    }

    private static int count(String table) throws SQLException {
        return Integer.parseInt(scalar("SELECT count(*)::text FROM " + table));
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = migratorConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void executeAsMigrator(String sql) throws SQLException {
        try (Connection connection = migratorConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection migratorConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "entitlement_migrator", "entitlement-migrator-password");
    }

    private static Connection appConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "entitlement_app", "entitlement-app-password");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/entitlement_db";
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
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
            basePackages = "io.saasforge.entitlement.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "entitlementSqlSessionFactory")
    @Import({
            MyBatisQuotaDefinitionRepository.class,
            MyBatisPlanRepository.class,
            MyBatisEntitlementBootstrapIdempotency.class,
            MyBatisEntitlementOutboxEventRepository.class
    })
    static class PersistenceConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl(), "entitlement_app", "entitlement-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory entitlementSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saasforge.entitlement.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory entitlementSqlSessionFactory) {
            return new SqlSessionTemplate(entitlementSqlSessionFactory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        UuidV7Generator ids(Clock clock) {
            return new UuidV7Generator(clock, new SecureRandom());
        }

        @Bean
        EntitlementEventFactory events(ObjectMapper objectMapper, UuidV7Generator ids) {
            return new EntitlementEventFactory(
                    objectMapper, ids, "saasforge.test.entitlement-service.events");
        }

        @Bean
        EntitlementBootstrapService service(
                MyBatisQuotaDefinitionRepository quotaDefinitions,
                MyBatisPlanRepository plans,
                MyBatisEntitlementBootstrapIdempotency idempotency,
                MyBatisEntitlementOutboxEventRepository outbox,
                EntitlementEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new EntitlementBootstrapService(
                    quotaDefinitions, plans, idempotency, outbox, events, ids, clock);
        }
    }
}
