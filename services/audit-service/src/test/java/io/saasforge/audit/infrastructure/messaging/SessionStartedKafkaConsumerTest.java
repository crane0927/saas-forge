package io.saasforge.audit.infrastructure.messaging;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.saasforge.audit.application.SessionStartedAuditRecord;
import io.saasforge.audit.application.SessionStartedAuditService;
import java.time.Instant;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class SessionStartedKafkaConsumerTest {
    @Test
    void acknowledgesOnlyAfterTransactionalServiceReturns() {
        SessionStartedEventValidator validator = mock(SessionStartedEventValidator.class);
        SessionStartedAuditService service = mock(SessionStartedAuditService.class);
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var message = new ConsumerRecord<String, String>("topic", 0, 1, "key", "payload");
        var record = new SessionStartedAuditRecord(
                UUID.fromString("019535d9-0001-7000-8000-000000000001"),
                SessionStartedEventValidator.SOURCE,
                SessionStartedEventValidator.EVENT_TYPE,
                Instant.parse("2026-08-28T10:15:30Z"),
                null,
                UUID.fromString("019535d9-0001-7000-8000-000000000002"),
                UUID.fromString("019535d9-0001-7000-8000-000000000003"),
                "{}");
        when(validator.validate("topic", "key", SessionStartedEventValidator.CONSUMER_NAME, "payload"))
                .thenReturn(record);

        new SessionStartedKafkaConsumer(validator, service).consume(message, acknowledgment);

        var order = inOrder(service, acknowledgment);
        order.verify(service).record(SessionStartedEventValidator.CONSUMER_NAME, record);
        order.verify(acknowledgment).acknowledge();
    }
}
