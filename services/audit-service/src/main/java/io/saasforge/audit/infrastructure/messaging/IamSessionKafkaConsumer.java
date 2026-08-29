package io.saasforge.audit.infrastructure.messaging;

import io.saasforge.audit.application.AuditRecordService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class IamSessionKafkaConsumer {
    private final IamSessionEventValidator validator;
    private final AuditRecordService service;

    public IamSessionKafkaConsumer(IamSessionEventValidator validator, AuditRecordService service) {
        this.validator = validator;
        this.service = service;
    }

    /** Acknowledgment 必须发生在本地事务方法返回之后，以便提交后故障由去重键吸收。 */
    @KafkaListener(
            topics = "${saasforge.audit.iam-session-topic}",
            groupId = SessionStartedEventValidator.CONSUMER_NAME)
    public void consume(ConsumerRecord<String, String> message, Acknowledgment acknowledgment) {
        var record = validator.validate(
                message.topic(), message.key(), SessionStartedEventValidator.CONSUMER_NAME, message.value());
        service.record(SessionStartedEventValidator.CONSUMER_NAME, record);
        acknowledgment.acknowledge();
    }
}
