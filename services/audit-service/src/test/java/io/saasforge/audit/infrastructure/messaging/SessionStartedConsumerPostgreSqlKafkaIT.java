package io.saasforge.audit.infrastructure.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.audit.application.SessionStartedAuditService;
import io.saasforge.audit.infrastructure.persistence.JdbcAuditRecordRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class SessionStartedConsumerPostgreSqlKafkaIT {
    private static final String TOPIC = "saasforge.test.iam-service.events";
    private static final String APP_PASSWORD = "audit-app-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    private static JdbcTemplate admin;
    private static JdbcTemplate app;
    private static SessionStartedKafkaConsumer listener;

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
        var appDataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "audit_app", APP_PASSWORD);
        app = new JdbcTemplate(appDataSource);
        var serviceTarget = new SessionStartedAuditService(
                new JdbcAuditRecordRepository(app),
                Clock.fixed(Instant.parse("2026-08-28T10:16:00Z"), ZoneOffset.UTC));
        ProxyFactory serviceProxy = new ProxyFactory(serviceTarget);
        serviceProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(appDataSource), new AnnotationTransactionAttributeSource()));
        var service = (SessionStartedAuditService) serviceProxy.getProxy();
        listener = new SessionStartedKafkaConsumer(
                new SessionStartedEventValidator(new ObjectMapper(), TOPIC), service);
    }

    @AfterEach
    void removeFailureTrigger() {
        admin.execute("DROP TRIGGER IF EXISTS fail_audit_record_insert ON audit_records");
        admin.execute("DROP FUNCTION IF EXISTS fail_audit_record_insert()");
    }

    @Test
    void writesOnceAndSafelyAbsorbsRedeliveryAfterCommitBeforeAcknowledgmentFailure() {
        String eventId = uuidV7(11);
        String payload = event(eventId);
        send(payload);
        String group = "audit-redelivery-" + UUID.randomUUID();

        try (KafkaConsumer<String, String> first = consumer(group)) {
            ConsumerRecord<String, String> message = pollOne(first);
            assertThrows(RuntimeException.class,
                    () -> listener.consume(message, () -> { throw new RuntimeException("simulated crash"); }));
        }

        try (KafkaConsumer<String, String> redelivery = consumer(group)) {
            ConsumerRecord<String, String> message = pollOne(redelivery);
            listener.consume(message, redelivery::commitSync);
        }

        assertEquals(1, count("audit_records", eventId));
        assertEquals(1, count("audit_consumed_events", eventId));
        assertEquals("{\"purpose\": \"USER_TENANT\", \"contextType\": \"TENANT\", "
                        + "\"sessionOutcome\": \"ACCESS_TOKEN_ISSUED\"}",
                app.queryForObject(
                        "SELECT metadata::text FROM audit_records WHERE source_event_id = ?::uuid",
                        String.class, eventId));
        assertEquals(0, app.queryForObject(
                "SELECT count(*) FROM audit_records WHERE source_event_id = ?::uuid AND tenant_id IS NOT NULL",
                Integer.class, eventId));
    }

    @Test
    void rollsBackDeduplicationWhenAuditRecordInsertFailsAndDoesNotAcknowledge() {
        String eventId = uuidV7(12);
        admin.execute("""
                CREATE FUNCTION fail_audit_record_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced audit insert failure'; END
                $$
                """);
        admin.execute("""
                CREATE TRIGGER fail_audit_record_insert BEFORE INSERT ON audit_records
                FOR EACH STATEMENT EXECUTE FUNCTION fail_audit_record_insert()
                """);
        Acknowledgment acknowledgment = org.mockito.Mockito.mock(Acknowledgment.class);

        assertThrows(DataAccessException.class, () -> listener.consume(
                new ConsumerRecord<>(TOPIC, 0, 0, identityId(), event(eventId)), acknowledgment));

        org.mockito.Mockito.verifyNoInteractions(acknowledgment);
        assertEquals(0, count("audit_records", eventId));
        assertEquals(0, count("audit_consumed_events", eventId));
    }

    @Test
    void runtimeRoleCanOnlySelectAndInsertAppendOnlyTables() {
        assertThrows(DataAccessException.class, () -> app.update("UPDATE audit_records SET result = result"));
        assertThrows(DataAccessException.class, () -> app.update("DELETE FROM audit_records"));
        assertThrows(DataAccessException.class, () -> app.execute("TRUNCATE audit_records"));
        assertEquals(0, admin.queryForObject(
                "SELECT count(*) FROM pg_policies WHERE tablename = 'audit_records'", Integer.class));
        assertEquals(Boolean.FALSE, admin.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'audit_records'", Boolean.class));
    }

    private static void send(String payload) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"))) {
            try {
                producer.send(new ProducerRecord<>(TOPIC, identityId(), payload)).get();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
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
        throw new AssertionError("未在期限内收到 Kafka 消息");
    }

    private static int count(String table, String eventId) {
        String eventColumn = table.equals("audit_records") ? "source_event_id" : "event_id";
        return app.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + eventColumn + " = ?::uuid",
                Integer.class, eventId);
    }

    private static String event(String eventId) {
        return SessionStartedEventValidatorTest.event("")
                .replace("019535d9-0001-7000-8000-000000000001", eventId);
    }

    private static String uuidV7(int suffix) {
        return "019535d9-0001-7000-8000-%012d".formatted(suffix);
    }

    private static String identityId() {
        return "019535d9-0001-7000-8000-000000000002";
    }
}
