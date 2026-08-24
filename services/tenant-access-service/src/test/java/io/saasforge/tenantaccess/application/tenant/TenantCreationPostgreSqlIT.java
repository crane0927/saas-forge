package io.saasforge.tenantaccess.application.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.saasforge.tenantaccess.application.administrator.IdentityCredentialDisposition;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupRepository;
import io.saasforge.tenantaccess.application.administrator.AdministratorPasswordSetupWorkflow;
import io.saasforge.tenantaccess.application.administrator.IdentityProvisioningGateway;
import io.saasforge.tenantaccess.application.administrator.InitializationRecoveryPolicy;
import io.saasforge.tenantaccess.application.administrator.InitializationWorkflow;
import io.saasforge.tenantaccess.application.administrator.InitializationWorkflowState;
import io.saasforge.tenantaccess.application.administrator.InitializationQuotaGateway;
import io.saasforge.tenantaccess.application.administrator.InitializeTenantAdministratorService;
import io.saasforge.tenantaccess.application.administrator.RemoteWorkflowUnavailableException;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationResult;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializedEventFactory;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationRepository;
import io.saasforge.tenantaccess.application.administrator.TenantAdministratorInitializationException;
import io.saasforge.tenantaccess.domain.outbox.OutboxEventRepository;
import io.saasforge.tenantaccess.domain.tenant.TenantStatus;
import io.saasforge.tenantaccess.infrastructure.messaging.TenantAccessOutboxPublisher;
import io.saasforge.tenantaccess.infrastructure.persistence.MyBatisTenantAccessOutboxEventRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.MyBatisAdministratorPasswordSetupRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.MyBatisTenantAdministratorInitializationRepository;
import io.saasforge.tenantaccess.infrastructure.persistence.MyBatisTenantCreationIdempotency;
import io.saasforge.tenantaccess.infrastructure.persistence.MyBatisTenantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringJUnitConfig(TenantCreationPostgreSqlIT.PersistenceConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantCreationPostgreSqlIT {
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

    @Container
    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    private CreatePendingTenantService service;

    @Autowired
    private InitialSubscriptionEligibilityService eligibility;

    @Autowired
    private TenantAdministratorInitializationRepository administratorInitialization;

    @Autowired
    private AdministratorPasswordSetupRepository administratorPasswordSetups;

    @Autowired
    private TenantAccessOutboxPublisher outboxPublisher;

    @BeforeAll
    void migrate() {
        Flyway.configure()
                .dataSource(jdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password")
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @BeforeEach
    void clean() throws SQLException {
        executeAsMigrator("TRUNCATE tenant_access_outbox_events, tenant_creation_idempotency, memberships, tenants CASCADE");
    }

    @AfterAll
    void stop() {
        KAFKA.stop();
        POSTGRES.stop();
    }

    @Test
    void commitsTenantStableResponseAndCreatedOutboxTogether() throws SQLException {
        UUID actor = uuidV7(1);
        TenantCreationResult result = service.create(
                actor, uuidV7(2), "Atomic Tenant", Instant.now().plusSeconds(3600),
                "11111111111111111111111111111111");
        TenantCreationResult replay = service.create(
                actor, uuidV7(2), "Atomic Tenant", result.expiresAt(),
                "11111111111111111111111111111111");

        assertEquals(result, replay);
        assertEquals(1, count("tenants"));
        assertEquals(1, count("tenant_creation_idempotency"));
        assertEquals(1, count("tenant_access_outbox_events"));
        assertEquals("PENDING", scalar("SELECT tenant_status FROM tenants WHERE id = '" + result.id() + "'"));
        assertTrue(scalar("SELECT event_snapshot::text FROM tenant_access_outbox_events")
                .contains("com.saasforge.tenant.created.v1"));
    }

    @Test
    void publishesCommittedTenantFactThroughKafkaAndMarksPostgreSqlOutbox() throws SQLException {
        try (KafkaConsumer<String, String> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of("saasforge.test.tenant-access-service.events"));
            consumer.poll(Duration.ofMillis(250));
            TenantCreationResult tenant = service.create(
                    uuidV7(5), uuidV7(6), "Kafka Tenant", Instant.now().plusSeconds(3600), null);

            outboxPublisher.publishNext();
            ConsumerRecord<String, String> event = awaitEvent(consumer);

            assertEquals(tenant.id().toString(), event.key());
            assertTrue(event.value().contains("com.saasforge.tenant.created.v1"));
            assertEquals("true", scalar("SELECT (published_at IS NOT NULL)::text FROM tenant_access_outbox_events"));
        }
    }

    @Test
    void activatesTenantWithOneInitialAdministratorRoleAndPendingPasswordDelivery() throws SQLException {
        UUID actor = uuidV7(60);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(61), "Admin Tenant", Instant.now().plusSeconds(3600), null);
        Instant now = Instant.now();
        InitializationWorkflow workflow = new InitializationWorkflow(
                uuidV7(62), tenant.id(), actor, uuidV7(63), "a".repeat(64),
                "admin@example.com", "Admin", uuidV7(64), uuidV7(65), uuidV7(66), uuidV7(67),
                null, null, null, now);

        InitializationWorkflow prepared = administratorInitialization.prepare(workflow, now);
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "postgres-it", now, now.plusSeconds(30)).orElseThrow();
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                claimed, uuidV7(68), IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(1));
        InitializationWorkflow quotaConsumed = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusMillis(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaConsumed, now.plusMillis(3));
        TenantAdministratorInitializationResult result = administratorInitialization.activate(
                activating, uuidV7(68), IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(4));

        assertEquals("ACTIVE", result.status().name());
        assertEquals("ACTIVE", scalar("SELECT tenant_status FROM tenants WHERE id = '" + tenant.id() + "'"));
        assertEquals(1, count("memberships"));
        assertEquals(1, count("tenant_roles"));
        assertEquals(1, count("membership_role_assignments"));
        assertEquals(1, count("initial_tenant_administrators"));
        assertEquals(1, count("password_setup_delivery_work_items"));
        assertEquals("TENANT_ADMINISTRATOR", scalar("SELECT role_key FROM tenant_roles"));
        assertEquals("true", scalar("SELECT system_managed::text FROM tenant_roles"));
        assertEquals("SUCCESS", scalar("SELECT outcome_code FROM tenant_administrator_initialization_workflows"));
        assertEquals("200", scalar("SELECT response_status::text FROM tenant_administrator_initialization_workflows"));
        assertTrue(scalar("SELECT event_snapshot::text FROM tenant_access_outbox_events "
                + "WHERE event_snapshot->>'type' = 'com.saasforge.tenant.administrator-initialized.v1'")
                .contains(tenant.id().toString()));

        InitializationWorkflow replay = administratorInitialization.prepare(workflow, now.plusSeconds(1));
        assertEquals(result, replay.result());
        assertEquals(1, count("memberships"));
        assertEquals(1, count("tenant_roles"));

        InitializationWorkflow newKey = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(69), "f".repeat(64)), now.plusSeconds(1));
        assertEquals("TENANT_ALREADY_INITIALIZED", newKey.outcomeCode());

        administratorInitialization.completePasswordDelivery(activating, now.plusSeconds(2));
        assertEquals("COMPLETED", scalar("SELECT work_status FROM password_setup_delivery_work_items"));
    }

    @Test
    void passwordSetupResendUsesInitialRelationshipSupersedesOldWorkAndSerializesTenantLeases()
            throws SQLException {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(610);
        UUID identityId = uuidV7(611);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(612), "Resend Tenant", now.plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(613), "6".repeat(64)), now);
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "initialization", now, now.plusSeconds(30)).orElseThrow();
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                claimed, identityId, IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(1));
        InitializationWorkflow quotaReady = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusMillis(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaReady, now.plusMillis(3));
        administratorInitialization.activate(
                activating, identityId, IdentityCredentialDisposition.SETUP_ALLOWED, now.plusMillis(4));

        AdministratorPasswordSetupWorkflow first = administratorPasswordSetups.prepare(
                passwordSetupWorkflow(tenant.id(), actor, uuidV7(614), uuidV7(615), uuidV7(616), now), now);
        AdministratorPasswordSetupWorkflow second = administratorPasswordSetups.prepare(
                passwordSetupWorkflow(tenant.id(), actor, uuidV7(617), uuidV7(618), uuidV7(619),
                        now.plusMillis(1)), now.plusMillis(1));

        assertEquals(identityId, first.administratorIdentityId());
        AdministratorPasswordSetupWorkflow firstLease = administratorPasswordSetups.claim(
                first.workflowId(), "resend-a", now.plusSeconds(31), now.plusSeconds(61)).orElseThrow();
        assertEquals("SUPERSEDED", scalar("SELECT work_status FROM password_setup_delivery_work_items"));
        assertTrue(administratorPasswordSetups.claim(
                second.workflowId(), "resend-b", now.plusSeconds(31), now.plusSeconds(61)).isEmpty());

        administratorPasswordSetups.scheduleRetry(
                firstLease, now.plusSeconds(40), "RemoteWorkflowUnavailableException");
        assertTrue(administratorPasswordSetups.claimNext(
                "resend-worker", now.plusSeconds(32), now.plusSeconds(62)).isEmpty());
        AdministratorPasswordSetupWorkflow retriedFirst = administratorPasswordSetups.claimNext(
                "resend-worker", now.plusSeconds(40), now.plusSeconds(70)).orElseThrow();
        assertEquals(first.workflowId(), retriedFirst.workflowId());
        administratorPasswordSetups.completeSuccess(retriedFirst, now.plusSeconds(41));
        AdministratorPasswordSetupWorkflow secondLease = administratorPasswordSetups.claimNext(
                "resend-b", now.plusSeconds(41), now.plusSeconds(71)).orElseThrow();
        assertEquals(second.deliveryRequestId(), secondLease.deliveryRequestId());
        assertEquals("true", scalar("SELECT relforcerowsecurity::text FROM pg_class "
                + "WHERE relname = 'administrator_password_setup_workflows'"));
        assertEquals("0", scalar("SELECT count(*)::text FROM information_schema.columns "
                + "WHERE table_name = 'administrator_password_setup_workflows' "
                + "AND column_name IN ('email', 'recipient', 'token', 'token_digest', 'mail_content')"));
    }

    @Test
    void passwordSetupWorkerReclaimsSameDurableRequestAfterRestart() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(620);
        UUID identityId = uuidV7(621);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(622), "Resend Recovery Tenant", now.plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(623), "7".repeat(64)), now);
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "initialization", now, now.plusSeconds(30)).orElseThrow();
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                claimed, identityId, IdentityCredentialDisposition.PASSWORD_READY, now.plusMillis(1));
        InitializationWorkflow quotaReady = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusMillis(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaReady, now.plusMillis(3));
        administratorInitialization.activate(
                activating, identityId, IdentityCredentialDisposition.PASSWORD_READY, now.plusMillis(4));

        AdministratorPasswordSetupWorkflow workflow = administratorPasswordSetups.prepare(
                passwordSetupWorkflow(tenant.id(), actor, uuidV7(624), uuidV7(625), uuidV7(626), now), now);
        AdministratorPasswordSetupWorkflow firstLease = administratorPasswordSetups.claim(
                workflow.workflowId(), "process-a", now.plusSeconds(31), now.plusSeconds(61)).orElseThrow();
        administratorPasswordSetups.scheduleRetry(
                firstLease, now.plusSeconds(32), "RemoteWorkflowUnavailableException");

        AdministratorPasswordSetupWorkflow restarted = administratorPasswordSetups.claimNext(
                "process-b", now.plusSeconds(32), now.plusSeconds(62)).orElseThrow();
        assertEquals(workflow.workflowId(), restarted.workflowId());
        assertEquals(workflow.deliveryRequestId(), restarted.deliveryRequestId());
        assertEquals(firstLease.attemptCount() + 1, restarted.attemptCount());
    }

    @Test
    void persistsStableQuotaFailureThroughForcedRls() throws SQLException {
        UUID actor = uuidV7(80);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(81), "Quota Tenant", Instant.now().plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(82), "e".repeat(64)), Instant.now());
        InitializationWorkflow claimed = administratorInitialization.claim(
                prepared.workflowId(), "postgres-it", Instant.now(), Instant.now().plusSeconds(30)).orElseThrow();

        administratorInitialization.completeFailure(
                claimed, "QUOTA_EXCEEDED", Instant.now());
        InitializationWorkflow replay = administratorInitialization.prepare(prepared, Instant.now());

        assertEquals("QUOTA_EXCEEDED", replay.outcomeCode());
        assertEquals("409", scalar("SELECT response_status::text FROM tenant_administrator_initialization_workflows"));
        assertEquals("true", scalar("SELECT relforcerowsecurity::text FROM pg_class "
                + "WHERE relname = 'tenant_administrator_initialization_workflows'"));
    }

    @Test
    void workerClaimRestoresAuthoritativeTargetAndExpiredLeaseTakeoverFencesOldExecutor() {
        Instant now = Instant.now();
        UUID actor = uuidV7(90);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(91), "Lease Tenant", now.plusSeconds(3600), null);
        InitializationWorkflow prepared = administratorInitialization.prepare(
                workflow(tenant.id(), actor, uuidV7(92), "9".repeat(64)), now);

        InitializationWorkflow first = administratorInitialization.claimNext(
                "worker-a", now, now.plusSeconds(10)).orElseThrow();
        assertEquals(prepared.workflowId(), first.workflowId());
        assertEquals(tenant.id(), first.tenantId());
        InitializationWorkflow identityReady = administratorInitialization.completeIdentity(
                first, uuidV7(93), IdentityCredentialDisposition.PASSWORD_READY, now.plusSeconds(1));
        InitializationWorkflow quotaConsumed = administratorInitialization.completeQuotaConsumption(
                identityReady, now.plusSeconds(2));
        InitializationWorkflow activating = administratorInitialization.beginActivation(
                quotaConsumed, now.plusSeconds(3));

        assertTrue(administratorInitialization.claimNext(
                "worker-b", now.plusSeconds(9), now.plusSeconds(20)).isEmpty());
        InitializationWorkflow takeover = administratorInitialization.claimNext(
                "worker-b", now.plusSeconds(11), now.plusSeconds(21)).orElseThrow();
        assertEquals(InitializationWorkflowState.ACTIVATING, takeover.state());
        assertEquals(activating.attemptCount() + 1, takeover.attemptCount());
        assertThrows(IllegalStateException.class,
                () -> administratorInitialization.beginCompensation(activating, now.plusSeconds(12)));
        InitializationWorkflow compensating = administratorInitialization.beginCompensation(
                takeover, now.plusSeconds(12));
        assertEquals(InitializationWorkflowState.COMPENSATING, compensating.state());
    }

    @Test
    void workerRestartExhaustionRemainsDiagnosticAndExplicitReplayPublishesThroughKafka() throws SQLException {
        Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(94);
        UUID idempotencyKey = uuidV7(95);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(96), "Restart Tenant", now.plusSeconds(3600), null);
        AtomicBoolean identityAvailable = new AtomicBoolean();
        List<UUID> identityRequests = new ArrayList<>();
        IdentityProvisioningGateway identities = (requestId, email, displayName) -> {
            identityRequests.add(requestId);
            if (!identityAvailable.get()) {
                throw new RemoteWorkflowUnavailableException(new IllegalStateException("IAM unavailable"));
            }
            return new IdentityProvisioningGateway.Result(uuidV7(97), IdentityCredentialDisposition.PASSWORD_READY);
        };
        InitializationQuotaGateway quota = new InitializationQuotaGateway() {
            @Override
            public void consume(UUID tenantId, UUID operationId) {
            }

            @Override
            public void release(UUID tenantId, UUID operationId) {
            }
        };
        InitializationRecoveryPolicy policy = new InitializationRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 2);
        InitializeTenantAdministratorService firstProcess = new InitializeTenantAdministratorService(
                administratorInitialization, identities, quota, (requestId, identityId) -> { },
                new UuidV7Generator(Clock.fixed(now, ZoneOffset.UTC), new SecureRandom()),
                Clock.fixed(now, ZoneOffset.UTC), policy, "process-a");

        assertThrows(RemoteWorkflowUnavailableException.class, () -> firstProcess.initialize(
                actor, idempotencyKey, tenant.id(), "admin@example.com", "Admin", null));

        InitializeTenantAdministratorService restartedWorker = new InitializeTenantAdministratorService(
                administratorInitialization, identities, quota, (requestId, identityId) -> { },
                new UuidV7Generator(Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC), new SecureRandom()),
                Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC), policy, "process-b");
        assertTrue(restartedWorker.recoverNext());
        assertEquals("true", scalar("SELECT (recovery_exhausted_at IS NOT NULL)::text "
                + "FROM tenant_administrator_initialization_workflows"));
        assertEquals("RemoteWorkflowUnavailableException", scalar(
                "SELECT last_failure FROM tenant_administrator_initialization_workflows"));
        assertNull(scalar("SELECT outcome_code FROM tenant_administrator_initialization_workflows"));
        assertFalse(restartedWorker.recoverNext());

        identityAvailable.set(true);
        TenantAdministratorInitializationResult result = restartedWorker.initialize(
                actor, idempotencyKey, tenant.id(), "admin@example.com", "Admin", null);
        assertEquals(TenantStatus.ACTIVE, result.status());
        assertEquals(3, identityRequests.size());
        assertEquals(1, identityRequests.stream().distinct().count());
        assertEquals("false", scalar("SELECT (recovery_exhausted_at IS NOT NULL)::text "
                + "FROM tenant_administrator_initialization_workflows"));

        try (KafkaConsumer<String, String> consumer = kafkaConsumer()) {
            consumer.subscribe(List.of("saasforge.test.tenant-access-service.events"));
            consumer.poll(Duration.ofMillis(250));
            outboxPublisher.publishNext();
            outboxPublisher.publishNext();
            ConsumerRecord<String, String> event = awaitEventContaining(
                    consumer, TenantAdministratorInitializedEventFactory.EVENT_TYPE);
            assertEquals(tenant.id().toString(), event.key());
        }
    }

    @Test
    void localActivationCommitFailureUsesStableReleaseAndRequiresNewAttempt() throws SQLException {
        Instant now = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.MILLIS);
        UUID actor = uuidV7(100);
        TenantCreationResult tenant = service.create(
                actor, uuidV7(101), "Compensation Tenant", now.plusSeconds(3600), null);
        List<UUID> consumed = new ArrayList<>();
        List<UUID> released = new ArrayList<>();
        AtomicBoolean releaseUnavailable = new AtomicBoolean(true);
        InitializationQuotaGateway quota = new InitializationQuotaGateway() {
            @Override
            public void consume(UUID tenantId, UUID operationId) {
                consumed.add(operationId);
            }

            @Override
            public void release(UUID tenantId, UUID operationId) {
                released.add(operationId);
                if (releaseUnavailable.get()) {
                    throw new RemoteWorkflowUnavailableException(
                            new IllegalStateException("Entitlement unavailable"));
                }
            }
        };
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InitializationRecoveryPolicy policy = new InitializationRecoveryPolicy(
                Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofMinutes(1), 10);
        InitializeTenantAdministratorService initialization = new InitializeTenantAdministratorService(
                administratorInitialization,
                (requestId, email, displayName) -> new IdentityProvisioningGateway.Result(
                        uuidV7(102), IdentityCredentialDisposition.PASSWORD_READY),
                quota,
                (requestId, identityId) -> { },
                new UuidV7Generator(clock, new SecureRandom()), clock, policy, "process-a");

        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_tenant_admin_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced local activation failure'; END $$;
                CREATE TRIGGER fail_tenant_admin_outbox BEFORE INSERT ON tenant_access_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_tenant_admin_outbox()
                """);
        UUID firstKey = uuidV7(103);
        try {
            TenantAdministratorInitializationException failure = assertThrows(
                    TenantAdministratorInitializationException.class,
                    () -> initialization.initialize(
                            actor, firstKey, tenant.id(), "admin@example.com", "Admin", null));
            assertEquals("TENANT_ADMIN_INITIALIZATION_COMPENSATING", failure.code());
        } finally {
            executeAsMigrator("DROP TRIGGER fail_tenant_admin_outbox ON tenant_access_outbox_events; "
                    + "DROP FUNCTION fail_tenant_admin_outbox()");
        }

        assertEquals(1, consumed.size());
        assertEquals(List.of(UUID.fromString(scalar(
                "SELECT release_operation_id::text FROM tenant_administrator_initialization_workflows "
                        + "WHERE idempotency_key = '" + firstKey + "'"))), released);
        assertEquals("COMPENSATING", scalar(
                "SELECT workflow_state FROM tenant_administrator_initialization_workflows "
                        + "WHERE idempotency_key = '" + firstKey + "'"));
        assertEquals("PENDING", scalar("SELECT tenant_status FROM tenants WHERE id = '" + tenant.id() + "'"));
        assertEquals(0, count("memberships"));

        releaseUnavailable.set(false);
        Clock restartedClock = Clock.fixed(now.plusSeconds(2), ZoneOffset.UTC);
        InitializeTenantAdministratorService restartedWorker = new InitializeTenantAdministratorService(
                administratorInitialization,
                (requestId, email, displayName) -> new IdentityProvisioningGateway.Result(
                        uuidV7(102), IdentityCredentialDisposition.PASSWORD_READY),
                quota,
                (requestId, identityId) -> { },
                new UuidV7Generator(restartedClock, new SecureRandom()), restartedClock, policy, "process-b");
        assertTrue(restartedWorker.recoverNext());
        assertEquals(2, released.size());
        assertEquals(released.get(0), released.get(1));
        assertEquals("TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED", scalar(
                "SELECT outcome_code FROM tenant_administrator_initialization_workflows "
                        + "WHERE idempotency_key = '" + firstKey + "'"));

        TenantAdministratorInitializationException replay = assertThrows(
                TenantAdministratorInitializationException.class,
                () -> restartedWorker.initialize(
                        actor, firstKey, tenant.id(), "admin@example.com", "Admin", null));
        assertEquals("TENANT_ADMIN_INITIALIZATION_RETRY_REQUIRED", replay.code());
        assertEquals(1, consumed.size());
        assertEquals(2, released.size());

        UUID secondKey = uuidV7(104);
        TenantAdministratorInitializationResult result = restartedWorker.initialize(
                actor, secondKey, tenant.id(), "admin@example.com", "Admin", null);
        assertEquals(TenantStatus.ACTIVE, result.status());
        assertEquals(2, consumed.size());
        assertNotEquals(consumed.get(0), consumed.get(1));
    }

    @Test
    void rejectsExpiredTenantBeforeRemoteWorkAndSerializesConcurrentKeys() throws Exception {
        UUID expiredTenant = uuidV7(70);
        insertTenant(expiredTenant, "PENDING", Instant.now().minusSeconds(1));
        InitializationWorkflow expired = administratorInitialization.prepare(
                workflow(expiredTenant, uuidV7(71), uuidV7(72), "b".repeat(64)), Instant.now());
        assertEquals("TENANT_EXPIRY_REACHED", expired.outcomeCode());

        UUID concurrentTenant = uuidV7(73);
        insertTenant(concurrentTenant, "PENDING", Instant.now().plusSeconds(3600));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(() -> prepareOutcome(
                    workflow(concurrentTenant, uuidV7(74), uuidV7(75), "c".repeat(64)), start));
            Future<String> second = executor.submit(() -> prepareOutcome(
                    workflow(concurrentTenant, uuidV7(74), uuidV7(76), "d".repeat(64)), start));
            start.countDown();

            assertEquals(Set.of("PREPARED", "TENANT_ADMIN_INITIALIZATION_IN_PROGRESS"),
                    Set.of(first.get(), second.get()));
        } finally {
            executor.shutdownNow();
        }
    }

    private String prepareOutcome(InitializationWorkflow workflow, CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            return administratorInitialization.prepare(workflow, Instant.now()).completed()
                    ? "COMPLETED"
                    : "PREPARED";
        } catch (TenantAdministratorInitializationException exception) {
            return exception.code();
        }
    }

    private static InitializationWorkflow workflow(
            UUID tenantId, UUID actorIdentityId, UUID idempotencyKey, String fingerprint) {
        return new InitializationWorkflow(
                uuidV7(idempotencyKey.getLeastSignificantBits() & 0xfff), tenantId, actorIdentityId,
                idempotencyKey, fingerprint, "admin@example.com", "Admin",
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 100),
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 200),
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 300),
                uuidV7((idempotencyKey.getLeastSignificantBits() & 0xfff) + 400),
                null, null, null, Instant.now());
    }

    private static AdministratorPasswordSetupWorkflow passwordSetupWorkflow(
            UUID tenantId,
            UUID actorIdentityId,
            UUID idempotencyKey,
            UUID workflowId,
            UUID deliveryRequestId,
            Instant createdAt) {
        return new AdministratorPasswordSetupWorkflow(
                workflowId, tenantId, actorIdentityId, idempotencyKey,
                "8".repeat(64),
                null, deliveryRequestId, null, null, createdAt, 0, createdAt,
                null, null, null, null);
    }

    @Test
    void concurrentSameCallerAndKeyCreatesExactlyOneTenant() throws Exception {
        UUID actor = uuidV7(10);
        UUID key = uuidV7(11);
        Instant expiry = Instant.now().plusSeconds(3600);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<TenantCreationResult> first = executor.submit(() -> {
                start.await();
                return service.create(actor, key, "Concurrent", expiry, null);
            });
            Future<TenantCreationResult> second = executor.submit(() -> {
                start.await();
                return service.create(actor, key, "Concurrent", expiry, null);
            });
            start.countDown();

            assertEquals(first.get(), second.get());
        } finally {
            executor.shutdownNow();
        }
        assertEquals(1, count("tenants"));
        assertEquals(1, count("tenant_access_outbox_events"));
    }

    @Test
    void derivesInitialSubscriptionEligibilityThroughForcedTenantRls() throws SQLException {
        UUID eligibleTenant = uuidV7(50);
        UUID expiredTenant = uuidV7(51);
        UUID activeTenant = uuidV7(52);
        insertTenant(eligibleTenant, "PENDING", Instant.now().plusSeconds(3600));
        insertTenant(expiredTenant, "PENDING", Instant.now().minusSeconds(1));
        insertTenant(activeTenant, "ACTIVE", Instant.now().plusSeconds(3600));

        assertEquals(InitialSubscriptionEligibility.PENDING_ELIGIBLE, eligibility.check(eligibleTenant));
        assertEquals(InitialSubscriptionEligibility.EXPIRY_REACHED, eligibility.check(expiredTenant));
        assertEquals(InitialSubscriptionEligibility.INVALID_STATE, eligibility.check(activeTenant));
        assertEquals(InitialSubscriptionEligibility.NOT_FOUND, eligibility.check(uuidV7(53)));
    }

    private static void insertTenant(UUID tenantId, String status, Instant expiresAt) throws SQLException {
        executeAsMigrator("INSERT INTO tenants "
                + "(id, display_name, tenant_status, expires_at, created_at, updated_at) VALUES ('"
                + tenantId + "', 'Eligibility', '" + status + "', '" + expiresAt + "', now(), now())");
    }

    @Test
    void rejectsRequestFingerprintConflict() {
        UUID actor = uuidV7(20);
        UUID key = uuidV7(21);
        Instant expiry = Instant.now().plusSeconds(3600);
        service.create(actor, key, "First", expiry, null);

        assertThrows(IdempotencyKeyReusedException.class,
                () -> service.create(actor, key, "Second", expiry, null));
    }

    @Test
    void forcedRlsRejectsCrossTenantReadsAndAppRoleCannotBypass() throws SQLException {
        UUID actor = uuidV7(30);
        TenantCreationResult first = service.create(
                actor, uuidV7(31), "First", Instant.now().plusSeconds(3600), null);
        TenantCreationResult second = service.create(
                actor, uuidV7(32), "Second", Instant.now().plusSeconds(3600), null);

        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setTarget(connection, first.id());
            assertEquals(1, queryCount(connection, "SELECT count(*) FROM tenants WHERE id = ?", first.id()));
            assertEquals(0, queryCount(connection, "SELECT count(*) FROM tenants WHERE id = ?", second.id()));
            connection.rollback();
        }
        assertFalse(Boolean.parseBoolean(scalar(
                "SELECT rolbypassrls::text FROM pg_roles WHERE rolname = 'tenant_access_app'")));
        assertEquals("true", scalar("SELECT relforcerowsecurity::text FROM pg_class WHERE relname = 'tenants'"));
    }

    @Test
    void outboxFailureRollsBackTenantAndIdempotencyRecord() throws SQLException {
        executeAsMigrator("""
                CREATE OR REPLACE FUNCTION fail_tenant_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$;
                CREATE TRIGGER fail_tenant_outbox BEFORE INSERT ON tenant_access_outbox_events
                FOR EACH ROW EXECUTE FUNCTION fail_tenant_outbox()
                """);
        try {
            assertThrows(RuntimeException.class, () -> service.create(
                    uuidV7(40), uuidV7(41), "Rollback", Instant.now().plusSeconds(3600), null));
            assertEquals(0, count("tenants"));
            assertEquals(0, count("tenant_creation_idempotency"));
            assertEquals(0, count("tenant_access_outbox_events"));
        } finally {
            executeAsMigrator("DROP TRIGGER fail_tenant_outbox ON tenant_access_outbox_events; DROP FUNCTION fail_tenant_outbox()");
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

    private static void setTarget(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenantId.toString());
            statement.executeQuery();
        }
    }

    private static int queryCount(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static Connection migratorConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "tenant_access_migrator", "tenant-access-migrator-password");
    }

    private static Connection appConnection() throws SQLException {
        return java.sql.DriverManager.getConnection(
                jdbcUrl(), "tenant_access_app", "tenant-access-app-password");
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/tenant_access_db";
    }

    private static UUID uuidV7(long value) {
        return UUID.fromString("019535d9-0000-7000-8000-" + String.format("%012x", value));
    }

    private static ConsumerRecord<String, String> awaitEvent(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(Duration.ofMillis(500));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("未收到 Tenant Access Outbox 事件");
    }

    private static ConsumerRecord<String, String> awaitEventContaining(
            KafkaConsumer<String, String> consumer, String expectedContent) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (record.value().contains(expectedContent)) {
                    return record;
                }
            }
        }
        throw new AssertionError("未收到预期 Tenant Access Outbox 事件: " + expectedContent);
    }

    private static KafkaConsumer<String, String> kafkaConsumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "tenant-access-test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
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
            basePackages = "io.saasforge.tenantaccess.infrastructure.persistence.mapper",
            sqlSessionFactoryRef = "tenantAccessSqlSessionFactory")
    @Import({MyBatisTenantRepository.class, MyBatisTenantCreationIdempotency.class,
            MyBatisTenantAccessOutboxEventRepository.class,
            MyBatisTenantAdministratorInitializationRepository.class,
            MyBatisAdministratorPasswordSetupRepository.class})
    static class PersistenceConfiguration {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(jdbcUrl(), "tenant_access_app", "tenant-access-app-password");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory tenantAccessSqlSessionFactory(DataSource dataSource) throws Exception {
            SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
            factory.setDataSource(dataSource);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:mapper/*Mapper.xml"));
            factory.setTypeHandlersPackage("io.saasforge.tenantaccess.infrastructure.persistence.type");
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory tenantAccessSqlSessionFactory) {
            return new SqlSessionTemplate(tenantAccessSqlSessionFactory);
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
        KafkaTemplate<String, String> kafkaTemplate() {
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                    ProducerConfig.ACKS_CONFIG, "all",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }

        @Bean
        TenantAccessOutboxPublisher outboxPublisher(
                OutboxEventRepository repository,
                KafkaTemplate<String, String> kafkaTemplate,
                Clock clock) {
            return new TenantAccessOutboxPublisher(repository, kafkaTemplate, clock, Duration.ofSeconds(30));
        }

        @Bean
        TenantCreatedEventFactory eventFactory(ObjectMapper objectMapper, UuidV7Generator ids) {
            return new TenantCreatedEventFactory(
                    objectMapper, ids, "saasforge.test.tenant-access-service.events");
        }

        @Bean
        TenantAdministratorInitializedEventFactory administratorInitializedEventFactory(
                ObjectMapper objectMapper, UuidV7Generator ids) {
            return new TenantAdministratorInitializedEventFactory(
                    objectMapper, ids, "saasforge.test.tenant-access-service.events");
        }

        @Bean
        CreatePendingTenantService service(
                MyBatisTenantRepository tenants,
                MyBatisTenantCreationIdempotency idempotency,
                OutboxEventRepository outbox,
                TenantCreatedEventFactory events,
                UuidV7Generator ids,
                Clock clock) {
            return new CreatePendingTenantService(tenants, idempotency, outbox, events, ids, clock);
        }

        @Bean
        InitialSubscriptionEligibilityService eligibility(
                MyBatisTenantRepository tenants, Clock clock) {
            return new InitialSubscriptionEligibilityService(tenants, clock);
        }
    }
}
