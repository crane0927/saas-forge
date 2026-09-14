package io.saasforge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.saasforge.audit.application.AuditConsumerFailurePolicy;
import io.saasforge.audit.application.AuditConsumerIsolation;
import io.saasforge.audit.application.AuditConsumerIsolationService;
import io.saasforge.audit.application.AuditIsolationReplayRejectedException;
import io.saasforge.audit.application.AuditIsolationReplayRequestOutcome;
import io.saasforge.audit.application.AuditIsolationReplayService;
import io.saasforge.audit.infrastructure.persistence.JdbcAuditConsumerIsolationRepository;
import io.saasforge.audit.infrastructure.persistence.JdbcAuditIsolationReplayRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class AuditIsolationReplayPostgreSqlKafkaIT {
    private static final String APP_PASSWORD = "audit-app-replay-test-password";
    private static final String TOPIC = "saasforge.test.iam-service.events";
    private static final Instant NOW = Instant.parse("2026-08-29T08:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static AuditConsumerIsolationService isolations;
    private static AuditIsolationReplayService replays;
    private static DefaultKafkaProducerFactory<String, String> producerFactory;
    private static AuditIsolationReplayPublisher publisher;

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
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "audit_app", APP_PASSWORD);
        app = new JdbcTemplate(dataSource);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        isolations = transactional(
                new AuditConsumerIsolationService(new JdbcAuditConsumerIsolationRepository(app), clock),
                dataSource);
        replays = transactional(
                new AuditIsolationReplayService(new JdbcAuditIsolationReplayRepository(app), clock),
                dataSource);
        producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
        publisher = publisher(new KafkaTemplate<>(producerFactory), clock);
    }

    @BeforeEach
    void clearFixtures() {
        admin.execute("TRUNCATE audit_isolation_attempts, audit_isolation_replays, "
                + "audit_isolation_deliveries, audit_consumer_isolations");
    }

    @AfterAll
    static void closeProducer() {
        producerFactory.destroy();
    }

    @Test
    void replaysSafeSnapshotToOriginalTopicAndResolvesWithoutOverwritingHistory() {
        UUID eventId = uuidV7(1);
        String snapshot = snapshot(eventId);
        UUID isolationId = safeIsolation(101, eventId, snapshot);

        try (KafkaConsumer<String, String> consumer = consumer("audit-replay-success-" + UUID.randomUUID())) {
            seekToEnd(consumer);
            assertEquals(AuditIsolationReplayRequestOutcome.REQUESTED, publisher.replay(isolationId));
            ConsumerRecord<String, String> replayed = pollOne(consumer);

            assertEquals("identity-key", replayed.key());
            assertEquals(snapshot, replayed.value());
        }

        assertEquals("RESOLVED", status(isolationId));
        assertEquals(1, app.queryForObject(
                "SELECT count(*) FROM audit_isolation_replays WHERE isolation_id = ? AND published_at IS NOT NULL",
                Integer.class, isolationId));
        assertEquals(3, app.queryForObject("""
                SELECT count(*) FROM audit_isolation_attempts
                WHERE isolation_id = ? AND action IN ('REPLAY_REQUESTED', 'REPLAY_SENT', 'REPLAY_SUCCEEDED')
                """, Integer.class, isolationId));

        assertEquals(AuditIsolationReplayRequestOutcome.ALREADY_RESOLVED, publisher.replay(isolationId));
        assertEquals(1, app.queryForObject(
                "SELECT send_attempt_count FROM audit_isolation_replays WHERE isolation_id = ?",
                Integer.class, isolationId));
        assertEquals(2, app.queryForObject(
                "SELECT request_count FROM audit_isolation_replays WHERE isolation_id = ?",
                Integer.class, isolationId));
    }

    @Test
    void concurrentRequestsCreateOneReplayAndOneActiveSender() throws Exception {
        UUID isolationId = safeIsolation(102, uuidV7(2), snapshot(uuidV7(2)));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                return replays.request(isolationId);
            });
            var second = executor.submit(() -> {
                start.await();
                return replays.request(isolationId);
            });
            start.countDown();
            List<AuditIsolationReplayRequestOutcome> outcomes = List.of(first.get(), second.get());
            assertEquals(1, outcomes.stream()
                    .filter(AuditIsolationReplayRequestOutcome.REQUESTED::equals).count());
            assertEquals(1, outcomes.stream()
                    .filter(AuditIsolationReplayRequestOutcome.ALREADY_REQUESTED::equals).count());
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, app.queryForObject(
                "SELECT count(*) FROM audit_isolation_replays WHERE isolation_id = ?",
                Integer.class, isolationId));
        assertEquals(2, app.queryForObject(
                "SELECT request_count FROM audit_isolation_replays WHERE isolation_id = ?",
                Integer.class, isolationId));
    }

    @Test
    void rejectsMissingOrUnsafeIsolationWithStableResults() {
        UUID missing = uuidV7(3);
        var firstMissing = assertThrows(
                AuditIsolationReplayRejectedException.class, () -> replays.request(missing));
        var secondMissing = assertThrows(
                AuditIsolationReplayRejectedException.class, () -> replays.request(missing));
        assertEquals(firstMissing.getMessage(), secondMissing.getMessage());

        UUID unsafe = isolations.isolate(new AuditConsumerIsolation(
                "audit-service.iam-session-events", TOPIC, 0, 103, null, null, null, null,
                "a".repeat(64), "PERMANENT_VALIDATION", "InvalidAuditEventException",
                1, null, null));
        var firstUnsafe = assertThrows(
                AuditIsolationReplayRejectedException.class, () -> replays.request(unsafe));
        var secondUnsafe = assertThrows(
                AuditIsolationReplayRejectedException.class, () -> replays.request(unsafe));
        assertEquals(firstUnsafe.getMessage(), secondUnsafe.getMessage());
        assertEquals("REJECTED_NON_REPLAYABLE", status(unsafe));
        assertEquals(0, app.queryForObject(
                "SELECT count(*) FROM audit_isolation_replays WHERE isolation_id = ?",
                Integer.class, unsafe));
    }

    @Test
    @SuppressWarnings("unchecked")
    void failedReplayRemainsRequestedAndCanBeRetriedWithOriginalEvent() {
        UUID eventId = uuidV7(4);
        String snapshot = snapshot(eventId);
        UUID isolationId = safeIsolation(104, eventId, snapshot);
        KafkaTemplate<String, String> failedKafka = mock(KafkaTemplate.class);
        when(failedKafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(
                        new IllegalStateException("broker unavailable")));

        assertThrows(AuditIsolationReplayPublishException.class,
                () -> publisher(failedKafka, Clock.fixed(NOW, ZoneOffset.UTC)).replay(isolationId));
        assertEquals("REPLAY_REQUESTED", status(isolationId));
        assertEquals(1, app.queryForObject("""
                SELECT count(*) FROM audit_isolation_attempts
                WHERE isolation_id = ? AND action = 'REPLAY_FAILED'
                """, Integer.class, isolationId));

        Clock retryClock = Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC);
        try (KafkaConsumer<String, String> consumer = consumer("audit-replay-retry-" + UUID.randomUUID())) {
            seekToEnd(consumer);
            assertEquals(AuditIsolationReplayRequestOutcome.ALREADY_REQUESTED,
                    publisher(new KafkaTemplate<>(producerFactory), retryClock).replay(isolationId));
            assertEquals(snapshot, pollOne(consumer).value());
        }
        assertEquals("RESOLVED", status(isolationId));
    }

    private static AuditIsolationReplayPublisher publisher(
            KafkaTemplate<String, String> kafkaTemplate, Clock clock) {
        return new AuditIsolationReplayPublisher(
                replays, kafkaTemplate,
                new AuditConsumerFailurePolicy(10, Duration.ofSeconds(1), Duration.ofMinutes(1)),
                clock, Duration.ofSeconds(30));
    }

    private static UUID safeIsolation(long offset, UUID eventId, String snapshot) {
        return isolations.isolate(new AuditConsumerIsolation(
                "audit-service.iam-session-events", TOPIC, 0, offset, "identity-key", eventId,
                "urn:saasforge:iam-service", "com.saasforge.iam.session.started.v1",
                "b".repeat(64), "RETRY_EXHAUSTED", "DataAccessResourceFailureException",
                10, snapshot, "saasforge.test.audit-service.iam-session-isolations"));
    }

    private static String status(UUID isolationId) {
        return app.queryForObject(
                "SELECT status FROM audit_consumer_isolations WHERE isolation_id = ?",
                String.class, isolationId);
    }

    private static UUID uuidV7(int suffix) {
        return UUID.fromString("019535d9-0001-7000-8000-" + String.format("%012d", suffix));
    }

    private static String snapshot(UUID eventId) {
        return "{\"specversion\":\"1.0\",\"id\":\"" + eventId
                + "\",\"source\":\"urn:saasforge:iam-service\","
                + "\"type\":\"com.saasforge.iam.session.started.v1\","
                + "\"time\":\"2026-08-29T08:00:00Z\",\"traceId\":\"trace-replay\","
                + "\"data\":{\"identityId\":\"identity\"}}";
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC));
        return consumer;
    }

    private static ConsumerRecord<String, String> pollOne(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(deadline)) {
            var records = consumer.poll(Duration.ofMillis(250));
            if (!records.isEmpty()) {
                return records.iterator().next();
            }
        }
        throw new AssertionError("未在期限内收到 Kafka Replay 消息");
    }

    private static void seekToEnd(KafkaConsumer<String, String> consumer) {
        Instant deadline = Instant.now().plusSeconds(20);
        while (consumer.assignment().isEmpty() && Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(250));
        }
        if (consumer.assignment().isEmpty()) {
            throw new AssertionError("未在期限内获得 Kafka 分区");
        }
        consumer.seekToEnd(consumer.assignment());
        consumer.assignment().forEach(consumer::position);
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DriverManagerDataSource dataSource) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (T) proxy.getProxy();
    }
}
