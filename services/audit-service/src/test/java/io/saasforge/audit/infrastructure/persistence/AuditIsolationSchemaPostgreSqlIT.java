package io.saasforge.audit.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.audit.application.AuditConsumerIsolation;
import io.saasforge.audit.application.AuditConsumerIsolationService;
import io.saasforge.audit.application.AuditProcessingFailure;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AuditIsolationSchemaPostgreSqlIT {
    private static final String APP_PASSWORD = "audit-app-isolation-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static AuditConsumerIsolationService service;

    @BeforeAll
    static void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        admin.execute("CREATE ROLE audit_app LOGIN PASSWORD '" + APP_PASSWORD + "'");
        admin.execute("GRANT CONNECT ON DATABASE " + POSTGRES.getDatabaseName() + " TO audit_app");
        admin.execute("GRANT USAGE ON SCHEMA public TO audit_app");
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var appDataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), "audit_app", APP_PASSWORD);
        app = new JdbcTemplate(appDataSource);
        var serviceTarget = new AuditConsumerIsolationService(
                new JdbcAuditConsumerIsolationRepository(app),
                Clock.fixed(Instant.parse("2026-08-29T04:00:00Z"), ZoneOffset.UTC));
        ProxyFactory serviceProxy = new ProxyFactory(serviceTarget);
        serviceProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(appDataSource), new AnnotationTransactionAttributeSource()));
        service = (AuditConsumerIsolationService) serviceProxy.getProxy();
    }

    @BeforeEach
    void clearIsolationFixtures() {
        admin.execute("TRUNCATE audit_isolation_attempts, audit_isolation_deliveries, "
                + "audit_consumer_isolations");
    }

    @Test
    void grantsOnlyAppendOrRequiredStateTransitionPrivileges() {
        assertEquals(3, app.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'audit_consumer_isolations', 'audit_isolation_attempts', 'audit_isolation_deliveries')
                """, Integer.class));

        assertThrows(DataAccessException.class,
                () -> app.execute("TRUNCATE audit_consumer_isolations"));
        assertThrows(DataAccessException.class,
                () -> app.execute("TRUNCATE audit_isolation_attempts"));
        assertThrows(DataAccessException.class,
                () -> app.execute("TRUNCATE audit_isolation_deliveries"));
        assertThrows(DataAccessException.class,
                () -> app.update("DELETE FROM audit_consumer_isolations"));
        assertThrows(DataAccessException.class,
                () -> app.update("DELETE FROM audit_isolation_attempts"));
        assertThrows(DataAccessException.class,
                () -> app.update("DELETE FROM audit_isolation_deliveries"));

        assertEquals("YES", admin.queryForObject("""
                SELECT is_updatable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'audit_consumer_isolations'
                  AND column_name = 'status'
                """, String.class));
        assertEquals(Boolean.FALSE, admin.queryForObject("""
                SELECT has_column_privilege('audit_app',
                    'audit_consumer_isolations', 'payload_sha256', 'UPDATE')
                """, Boolean.class));
        assertEquals(Boolean.FALSE, admin.queryForObject("""
                SELECT has_column_privilege('audit_app',
                    'audit_isolation_deliveries', 'isolation_id', 'UPDATE')
                """, Boolean.class));
    }

    @Test
    void storesOnlyDigestForPermanentFailureAndCreatesSafeDeliveryAtomically() {
        String permanentDigest = "a".repeat(64);
        service.recordProcessingFailure(new AuditProcessingFailure(
                "audit-service.iam-session-events", "saasforge.test.iam-service.events",
                0, 41, null, null, null, null, permanentDigest,
                "PERMANENT_VALIDATION", "InvalidAuditEventException", 1));
        UUID permanentIsolation = service.isolate(new AuditConsumerIsolation(
                "audit-service.iam-session-events", "saasforge.test.iam-service.events",
                0, 41, null, null, null, null, permanentDigest,
                "PERMANENT_VALIDATION", "InvalidAuditEventException", 1, null, null));

        var permanent = app.queryForMap("""
                SELECT status, safe_snapshot, attempt_count
                FROM audit_consumer_isolations WHERE isolation_id = ?
                """, permanentIsolation);
        assertEquals("REJECTED_NON_REPLAYABLE", permanent.get("status"));
        assertNull(permanent.get("safe_snapshot"));
        assertEquals(1, permanent.get("attempt_count"));
        assertEquals(0, app.queryForObject(
                "SELECT count(*) FROM audit_isolation_deliveries WHERE isolation_id = ?",
                Integer.class, permanentIsolation));

        String eventId = "019535d9-0001-7000-8000-000000000083";
        String snapshot = "{\"id\":\"" + eventId + "\",\"type\":\"safe\"}";
        service.recordProcessingFailure(new AuditProcessingFailure(
                "audit-service.tenant-events", "saasforge.test.tenant-access-service.events",
                1, 42, "tenant-key", UUID.fromString(eventId),
                "urn:saasforge:tenant-access-service", "com.saasforge.tenant.created.v1",
                "b".repeat(64), "TRANSIENT_PROCESSING", "DataAccessResourceFailureException", 10));
        UUID safeIsolation = service.isolate(new AuditConsumerIsolation(
                "audit-service.tenant-events", "saasforge.test.tenant-access-service.events",
                1, 42, "tenant-key", UUID.fromString(eventId),
                "urn:saasforge:tenant-access-service", "com.saasforge.tenant.created.v1",
                "b".repeat(64), "RETRY_EXHAUSTED", "DataAccessResourceFailureException",
                10, snapshot, "saasforge.test.audit-service.tenant-isolations"));

        assertEquals(snapshot, app.queryForObject(
                "SELECT safe_snapshot FROM audit_consumer_isolations WHERE isolation_id = ?",
                String.class, safeIsolation));
        assertEquals(1, app.queryForObject(
                "SELECT count(*) FROM audit_isolation_deliveries WHERE isolation_id = ?",
                Integer.class, safeIsolation));
        assertEquals(13, app.queryForObject("""
                SELECT count(*) FROM audit_isolation_attempts WHERE record_offset IN (41, 42)
                """, Integer.class));
    }

    @Test
    void leaseCanBeRetakenAndDeliveryAttemptsRemainAppendOnly() {
        String eventId = "019535d9-0001-7000-8000-000000000084";
        String snapshot = "{\"id\":\"" + eventId + "\",\"type\":\"safe\"}";
        UUID isolationId = service.isolate(new AuditConsumerIsolation(
                "audit-service.iam-session-events", "saasforge.test.iam-service.events",
                0, 43, "identity-key", UUID.fromString(eventId),
                "urn:saasforge:iam-service", "com.saasforge.iam.session.started.v1",
                "c".repeat(64), "RETRY_EXHAUSTED", "IllegalStateException", 10, snapshot,
                "saasforge.test.audit-service.iam-session-isolations"));
        Instant firstClaimAt = Instant.parse("2026-08-29T04:00:00Z");
        var first = service.claimNext("publisher-a", firstClaimAt, firstClaimAt.plusSeconds(30))
                .orElseThrow();

        assertEquals(isolationId, first.isolationId());
        assertEquals(UUID.fromString(eventId), first.eventId());
        assertEquals(snapshot, first.eventSnapshot());
        assertEquals(1, first.attemptCount());
        service.releaseAfterFailure(first, firstClaimAt.plusSeconds(1), "TimeoutException");

        assertEquals(Optional.empty(), service.claimNext(
                "publisher-b", firstClaimAt.plusMillis(999), firstClaimAt.plusSeconds(31)));
        var second = service.claimNext(
                "publisher-b", firstClaimAt.plusSeconds(1), firstClaimAt.plusSeconds(31)).orElseThrow();
        assertEquals(2, second.attemptCount());
        service.markPublished(second, firstClaimAt.plusSeconds(2));

        assertEquals(2, app.queryForObject("""
                SELECT count(*) FROM audit_isolation_attempts
                WHERE isolation_id = ? AND action IN ('ISOLATION_DELIVERY_FAILED', 'ISOLATION_DELIVERED')
                """, Integer.class, isolationId));
        assertEquals(1, app.queryForObject("""
                SELECT count(*) FROM audit_isolation_deliveries
                WHERE isolation_id = ? AND published_at IS NOT NULL
                """, Integer.class, isolationId));
    }

    @Test
    void rollsBackSafeIsolationWhenReliableDeliveryStateCannotBeCreated() {
        admin.execute("""
                CREATE FUNCTION fail_audit_isolation_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced delivery insert failure'; END
                $$
                """);
        admin.execute("""
                CREATE TRIGGER fail_audit_isolation_delivery
                BEFORE INSERT ON audit_isolation_deliveries
                FOR EACH STATEMENT EXECUTE FUNCTION fail_audit_isolation_delivery()
                """);
        try {
            assertThrows(DataAccessException.class, () -> service.isolate(new AuditConsumerIsolation(
                    "audit-service.tenant-events", "saasforge.test.tenant-access-service.events",
                    0, 44, "tenant-key",
                    UUID.fromString("019535d9-0001-7000-8000-000000000085"),
                    "urn:saasforge:tenant-access-service", "com.saasforge.tenant.created.v1",
                    "d".repeat(64), "RETRY_EXHAUSTED", "IllegalStateException",
                    10, "{\"id\":\"019535d9-0001-7000-8000-000000000085\"}",
                    "saasforge.test.audit-service.tenant-isolations")));
        } finally {
            admin.execute("DROP TRIGGER fail_audit_isolation_delivery ON audit_isolation_deliveries");
            admin.execute("DROP FUNCTION fail_audit_isolation_delivery()");
        }

        assertEquals(0, app.queryForObject("""
                SELECT count(*) FROM audit_consumer_isolations WHERE record_offset = 44
                """, Integer.class));
        assertEquals(0, app.queryForObject("""
                SELECT count(*) FROM audit_isolation_attempts WHERE record_offset = 44
                """, Integer.class));
    }
}
