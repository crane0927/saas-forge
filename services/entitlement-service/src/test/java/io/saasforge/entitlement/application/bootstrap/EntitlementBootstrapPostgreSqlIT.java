package io.saasforge.entitlement.application.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandRequest;
import io.saasforge.contracts.entitlement.quota.v1.QuotaCommandServiceGrpc;
import io.saasforge.contracts.entitlement.quota.v1.QuotaPurpose;
import io.saasforge.entitlement.application.quota.QuotaCommandApplicationService;
import io.saasforge.entitlement.application.quota.QuotaCommandException;
import io.saasforge.entitlement.application.quota.QuotaOperationIdReusedException;
import io.saasforge.entitlement.domain.plan.PlanTransitionException;
import io.saasforge.entitlement.domain.quota.QuotaOperationOutcome;
import io.saasforge.entitlement.domain.quota.QuotaOperationPurpose;
import io.saasforge.entitlement.domain.quota.QuotaDefinitionTransitionException;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisEntitlementBootstrapIdempotency;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisEntitlementOutboxEventRepository;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisPlanRepository;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisQuotaDefinitionRepository;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisQuotaOperationRepository;
import io.saasforge.entitlement.infrastructure.persistence.MyBatisSubscriptionRepository;
import io.saasforge.entitlement.infrastructure.grpc.QuotaCommandGrpcService;
import io.saasforge.entitlement.infrastructure.grpc.QuotaCommandServerInterceptor;
import io.saasforge.sdk.auth.ServiceAccessTokenClaims;
import io.saasforge.sdk.auth.ServiceAccessTokenScopeException;
import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import io.saasforge.entitlement.application.subscription.CreateInitialSubscriptionService;
import io.saasforge.entitlement.application.subscription.TenantEligibilityGateway;
import io.saasforge.entitlement.domain.subscription.InitialSubscriptionAlreadyExistsException;
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
import java.util.concurrent.TimeUnit;
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

    @Autowired
    private CreateInitialSubscriptionService initialSubscriptions;

    @Autowired
    private QuotaCommandApplicationService quotaCommands;

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
                + "quota_operations, quota_usages, subscriptions, plan_quotas, plans, quota_definitions CASCADE");
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

    @Test
    void createsOnlyOneInitialSubscriptionWithStableReplayConcurrentExclusionAndRls() throws Exception {
        UUID actor = uuidV7(40);
        UUID tenant = uuidV7(41);
        UUID otherTenant = uuidV7(42);
        PlanResult plan = activePlan(actor, 43);
        var created = initialSubscriptions.create(
                actor, uuidV7(50), tenant, plan.id(), null, null);
        assertEquals(created, initialSubscriptions.create(
                actor, uuidV7(50), tenant, plan.id(), null, null));
        assertThrows(InitialSubscriptionAlreadyExistsException.class,
                () -> initialSubscriptions.create(actor, uuidV7(51), tenant, plan.id(), null, null));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentCreate(
                    start, actor, uuidV7(52), otherTenant, plan.id()));
            Future<Boolean> second = executor.submit(() -> concurrentCreate(
                    start, actor, uuidV7(53), otherTenant, plan.id()));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally {
            executor.shutdownNow();
        }

        assertEquals(2, count("subscriptions"));
        assertEquals(1, visibleSubscriptionCount(tenant));
        assertEquals(1, visibleSubscriptionCount(otherTenant));
        String snapshots = scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events");
        assertTrue(snapshots.contains("com.saasforge.subscription.created.v1"));
    }

    @Test
    void subscriptionOutboxFailureRollsBackSubscriptionAndIdempotency() throws SQLException {
        UUID actor = uuidV7(60);
        UUID tenant = uuidV7(61);
        PlanResult plan = activePlan(actor, 62);
        int idempotencyBefore = count("entitlement_bootstrap_idempotency");
        int outboxBefore = count("entitlement_outbox_events");
        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_entitlement_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$;
                CREATE TRIGGER fail_entitlement_outbox BEFORE INSERT ON entitlement_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_entitlement_outbox()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> initialSubscriptions.create(
                            actor, uuidV7(66), tenant, plan.id(), null, null));
            assertEquals(0, count("subscriptions"));
            assertEquals(idempotencyBefore, count("entitlement_bootstrap_idempotency"));
            assertEquals(outboxBefore, count("entitlement_outbox_events"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_entitlement_outbox ON entitlement_outbox_events; "
                    + "DROP FUNCTION fail_entitlement_outbox()");
        }
    }

    @Test
    void atomicallyConsumesAndReleasesWithStableReplayAndPurposeEvents() throws SQLException {
        UUID actor = uuidV7(70);
        UUID tenant = uuidV7(71);
        UUID caller = uuidV7(72);
        PlanResult plan = activePlan(actor, 73, 1);
        initialSubscriptions.create(actor, uuidV7(77), tenant, plan.id(), null, null);

        UUID consumeOperation = uuidV7(78);
        var consumed = quotaCommands.consume(caller, tenant, "max_users", 1, consumeOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        assertEquals(1, consumed.usage());
        assertEquals(1, consumed.limit());
        assertFalse(consumed.replayed());
        assertTrue(quotaCommands.consume(caller, tenant, "max_users", 1, consumeOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).replayed());
        assertThrows(QuotaOperationIdReusedException.class,
                () -> quotaCommands.release(caller, tenant, "max_users", 1, consumeOperation,
                        QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));

        UUID releaseOperation = uuidV7(79);
        assertEquals(0, quotaCommands.release(caller, tenant, "max_users", 1, releaseOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).usage());
        assertTrue(quotaCommands.release(caller, tenant, "max_users", 1, releaseOperation,
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).replayed());

        QuotaCommandException underflow = assertThrows(QuotaCommandException.class,
                () -> quotaCommands.release(caller, tenant, "max_users", 1, uuidV7(80),
                        QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));
        assertEquals(QuotaOperationOutcome.QUOTA_RELEASE_UNDERFLOW, underflow.outcome());
        String events = scalar("SELECT string_agg(event_snapshot::text, ' ') FROM entitlement_outbox_events");
        assertTrue(events.contains("com.saasforge.quota.consumed.v1"));
        assertTrue(events.contains("com.saasforge.quota.released.v1"));
        assertTrue(events.contains("TENANT_ADMIN_INITIALIZATION"));
        assertFalse(events.contains(caller.toString()));
    }

    @Test
    void concurrentConsumeNeverExceedsCurrentSubscriptionLimit() throws Exception {
        UUID actor = uuidV7(90);
        UUID tenant = uuidV7(91);
        UUID caller = uuidV7(92);
        PlanResult plan = activePlan(actor, 93, 3);
        initialSubscriptions.create(actor, uuidV7(97), tenant, plan.id(), null, null);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            var futures = new java.util.ArrayList<Future<Boolean>>();
            for (int index = 0; index < 12; index++) {
                UUID operationId = uuidV7(100 + index);
                futures.add(executor.submit(() -> concurrentConsume(start, caller, tenant, operationId)));
            }
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                successes += future.get() ? 1 : 0;
            }
            assertEquals(3, successes);
        } finally {
            executor.shutdownNow();
        }
        assertEquals("3", scalar("SELECT used::text FROM quota_usages WHERE tenant_id = '" + tenant + "'"));
        assertEquals(12, count("quota_operations"));
    }

    @Test
    void outboxFailureRollsBackQuotaUsageAndOperationAndRlsHidesOtherTenants() throws SQLException {
        UUID actor = uuidV7(120);
        UUID tenant = uuidV7(121);
        UUID otherTenant = uuidV7(122);
        UUID caller = uuidV7(123);
        PlanResult plan = activePlan(actor, 124, 2);
        initialSubscriptions.create(actor, uuidV7(128), tenant, plan.id(), null, null);
        initialSubscriptions.create(actor, uuidV7(129), otherTenant, plan.id(), null, null);

        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_quota_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.event_snapshot->>'type' = 'com.saasforge.quota.consumed.v1' THEN
                        RAISE EXCEPTION 'forced quota outbox failure';
                    END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER fail_quota_outbox BEFORE INSERT ON entitlement_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_quota_outbox()
                """);
        try {
            assertThrows(RuntimeException.class,
                    () -> quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(130),
                            QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION));
            assertEquals(0, count("quota_usages"));
            assertEquals(0, count("quota_operations"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_quota_outbox ON entitlement_outbox_events; "
                    + "DROP FUNCTION fail_quota_outbox()");
        }

        quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(131),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        quotaCommands.consume(caller, otherTenant, "max_users", 1, uuidV7(132),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        assertEquals(1, visibleTenantRows("quota_usages", tenant));
        assertEquals(1, visibleTenantRows("quota_usages", otherTenant));
        assertEquals(1, visibleTenantRows("quota_operations", tenant));
        assertEquals(1, visibleTenantRows("quota_operations", otherTenant));
        assertEquals(0, visibleTenantRowsWithoutTarget("quota_operations"));
    }

    @Test
    void grpcRejectsWrongScopeAndPurposeBeforeQuotaStateAndReturnsContractFields() throws Exception {
        ServiceAccessTokenVerifier tokens = mock(ServiceAccessTokenVerifier.class);
        UUID caller = uuidV7(140);
        when(tokens.verify("tenant-access-token", "entitlement:quota:write"))
                .thenReturn(new ServiceAccessTokenClaims(
                        caller, java.util.Set.of("entitlement:quota:write"), uuidV7(141),
                        java.time.Instant.EPOCH, java.time.Instant.EPOCH.plusSeconds(300)));
        doThrow(new ServiceAccessTokenScopeException())
                .when(tokens).verify("runtime-token", "entitlement:quota:write");
        QuotaCommandGrpcService grpc = new QuotaCommandGrpcService(quotaCommands);
        String serverName = InProcessServerBuilder.generateName();
        Server server = InProcessServerBuilder.forName(serverName).directExecutor()
                .addService(ServerInterceptors.intercept(grpc, new QuotaCommandServerInterceptor(tokens)))
                .build().start();
        ManagedChannel channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
        try {
            StatusRuntimeException wrongScope = assertThrows(StatusRuntimeException.class,
                    () -> quotaStub(channel, "runtime-token").consume(quotaRequest(uuidV7(142), uuidV7(143),
                            QuotaPurpose.TENANT_ADMIN_INITIALIZATION)));
            assertEquals(Status.Code.PERMISSION_DENIED, wrongScope.getStatus().getCode());
            assertEquals(0, count("quota_operations"));

            StatusRuntimeException wrongPurpose = assertThrows(StatusRuntimeException.class,
                    () -> quotaStub(channel, "tenant-access-token").consume(
                            quotaRequest(uuidV7(144), uuidV7(145), QuotaPurpose.RUNTIME)));
            assertEquals(Status.Code.INVALID_ARGUMENT, wrongPurpose.getStatus().getCode());
            assertEquals(0, count("quota_operations"));

            UUID actor = uuidV7(146);
            UUID tenant = uuidV7(147);
            PlanResult plan = activePlan(actor, 148, 2);
            initialSubscriptions.create(actor, uuidV7(152), tenant, plan.id(), null, null);
            var response = quotaStub(channel, "tenant-access-token").consume(
                    quotaRequest(tenant, uuidV7(153), QuotaPurpose.TENANT_ADMIN_INITIALIZATION));
            assertEquals(1, response.getUsage());
            assertEquals(2, response.getLimit());
            assertFalse(response.getReplayed());
        } finally {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void releaseStillCompensatesAfterSubscriptionExpires() throws SQLException {
        UUID actor = uuidV7(160);
        UUID tenant = uuidV7(161);
        UUID caller = uuidV7(162);
        PlanResult plan = activePlan(actor, 163, 1);
        initialSubscriptions.create(actor, uuidV7(167), tenant, plan.id(), null, null);
        quotaCommands.consume(caller, tenant, "max_users", 1, uuidV7(168),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
        executeAsMigrator("UPDATE subscriptions SET ends_at = created_at + interval '1 millisecond' "
                + "WHERE tenant_id = '" + tenant + "'");

        assertEquals(0, quotaCommands.release(caller, tenant, "max_users", 1, uuidV7(169),
                QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION).usage());
    }

    private boolean concurrentCreate(
            CountDownLatch start, UUID actor, UUID key, UUID tenantId, UUID planId) throws InterruptedException {
        start.await();
        try {
            initialSubscriptions.create(actor, key, tenantId, planId, null, null);
            return true;
        } catch (InitialSubscriptionAlreadyExistsException exception) {
            return false;
        }
    }

    private boolean concurrentConsume(
            CountDownLatch start, UUID caller, UUID tenantId, UUID operationId) throws InterruptedException {
        start.await();
        try {
            quotaCommands.consume(caller, tenantId, "max_users", 1, operationId,
                    QuotaOperationPurpose.TENANT_ADMIN_INITIALIZATION);
            return true;
        } catch (QuotaCommandException exception) {
            assertEquals(QuotaOperationOutcome.QUOTA_EXCEEDED, exception.outcome());
            return false;
        }
    }

    private PlanResult activePlan(UUID actor, long seed) {
        return activePlan(actor, seed, 10);
    }

    private PlanResult activePlan(UUID actor, long seed, int limit) {
        QuotaDefinitionResult quota = service.createQuotaDefinition(actor, uuidV7(seed), "max_users", null);
        service.activateQuotaDefinition(actor, uuidV7(seed + 1), quota.id(), null);
        PlanResult plan = service.createPlan(
                actor, uuidV7(seed + 2), "starter-" + seed, "Starter", quota.id(), limit, null);
        return service.activatePlan(actor, uuidV7(seed + 3), plan.id(), null);
    }

    private static int visibleTenantRows(String table, UUID tenantId) throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int visibleTenantRowsWithoutTarget(String table) throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static QuotaCommandRequest quotaRequest(
            UUID tenantId, UUID operationId, QuotaPurpose purpose) {
        return QuotaCommandRequest.newBuilder()
                .setTenantId(tenantId.toString())
                .setQuotaCode("max_users")
                .setAmount(1)
                .setOperationId(operationId.toString())
                .setPurpose(purpose)
                .build();
    }

    private static QuotaCommandServiceGrpc.QuotaCommandServiceBlockingStub quotaStub(
            ManagedChannel channel, String token) {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        return QuotaCommandServiceGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata));
    }

    private static int visibleSubscriptionCount(UUID tenantId) throws SQLException {
        try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.tenant_id', '" + tenantId + "', true)");
            try (ResultSet result = statement.executeQuery("SELECT count(*) FROM subscriptions")) {
                result.next();
                return result.getInt(1);
            }
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
            MyBatisQuotaOperationRepository.class,
            MyBatisPlanRepository.class,
            MyBatisSubscriptionRepository.class,
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

        @Bean
        TenantEligibilityGateway tenantEligibilityGateway() {
            return tenantId -> TenantEligibilityGateway.Outcome.PENDING_ELIGIBLE;
        }

        @Bean
        CreateInitialSubscriptionService initialSubscriptions(
                MyBatisPlanRepository plans,
                MyBatisSubscriptionRepository subscriptions,
                TenantEligibilityGateway tenantEligibility,
                MyBatisEntitlementBootstrapIdempotency idempotency,
                MyBatisEntitlementOutboxEventRepository outbox,
                EntitlementEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new CreateInitialSubscriptionService(
                    plans, subscriptions, tenantEligibility, idempotency, outbox, events, ids, clock);
        }

        @Bean
        QuotaCommandApplicationService quotaCommands(
                MyBatisQuotaOperationRepository operations,
                MyBatisEntitlementOutboxEventRepository outbox,
                EntitlementEventFactory events,
                Clock clock) {
            return new QuotaCommandApplicationService(operations, outbox, events, clock);
        }
    }
}
