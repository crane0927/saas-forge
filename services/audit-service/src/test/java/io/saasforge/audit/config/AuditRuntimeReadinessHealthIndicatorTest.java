package io.saasforge.audit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdminOperations;
import org.springframework.kafka.listener.MessageListenerContainer;

class AuditRuntimeReadinessHealthIndicatorTest {
    private static final String IAM_TOPIC = "saasforge.test.iam-service.events";
    private static final String TENANT_TOPIC = "saasforge.test.tenant-access-service.events";

    private JdbcOperations jdbc;
    private KafkaAdminOperations kafkaAdmin;
    private KafkaListenerEndpointRegistry listeners;
    private MessageListenerContainer iamListener;
    private MessageListenerContainer tenantListener;
    private AuditRuntimeReadinessHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcOperations.class);
        kafkaAdmin = mock(KafkaAdminOperations.class);
        listeners = mock(KafkaListenerEndpointRegistry.class);
        iamListener = mock(MessageListenerContainer.class);
        tenantListener = mock(MessageListenerContainer.class);
        indicator = new AuditRuntimeReadinessHealthIndicator(
                jdbc, kafkaAdmin, listeners, "5", IAM_TOPIC, TENANT_TOPIC);

        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("5"))).thenReturn(1);
        when(kafkaAdmin.describeTopics(IAM_TOPIC, TENANT_TOPIC)).thenReturn(Map.of(
                IAM_TOPIC, mock(TopicDescription.class),
                TENANT_TOPIC, mock(TopicDescription.class)));
        configureAssignment("audit-iam-session-events", iamListener, IAM_TOPIC);
        configureAssignment("audit-tenant-events", tenantListener, TENANT_TOPIC);
    }

    @Test
    void isReadyOnlyWhenMigrationKafkaAndBothAssignmentsAreAvailable() {
        assertEquals(Status.UP, indicator.health().getStatus());

        when(tenantListener.getAssignedPartitions()).thenReturn(List.of());

        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals("audit-tenant-events", indicator.health().getDetails().get("consumerAssignment"));
    }

    @Test
    void recoversWithoutRestartAfterDatabaseBecomesAvailable() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("5")))
                .thenThrow(new DataAccessResourceFailureException("unavailable"))
                .thenReturn(1);

        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void rejectsPendingMigrationAndUnavailableKafka() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("5"))).thenReturn(0);

        assertEquals("pending", indicator.health().getDetails().get("databaseMigration"));

        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("5"))).thenReturn(1);
        when(kafkaAdmin.describeTopics(IAM_TOPIC, TENANT_TOPIC))
                .thenThrow(new IllegalStateException("unavailable"));

        assertEquals("unavailable", indicator.health().getDetails().get("kafka"));
    }

    private void configureAssignment(
            String listenerId, MessageListenerContainer listener, String topic) {
        when(listeners.getListenerContainer(listenerId)).thenReturn(listener);
        when(listener.isRunning()).thenReturn(true);
        when(listener.getAssignedPartitions()).thenReturn(List.of(new TopicPartition(topic, 0)));
    }
}
