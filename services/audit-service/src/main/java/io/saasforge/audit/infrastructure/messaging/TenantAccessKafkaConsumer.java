package io.saasforge.audit.infrastructure.messaging;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.saasforge.audit.application.AuditRecordService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TenantAccessKafkaConsumer {
    private final TenantAccessEventValidator validator;
    private final AuditRecordService service;
    private final Counter ignored;

    public TenantAccessKafkaConsumer(
            TenantAccessEventValidator validator,
            AuditRecordService service,
            MeterRegistry meterRegistry) {
        this.validator = validator;
        this.service = service;
        this.ignored = Counter.builder("saasforge.audit.consumer.events")
                .tag("consumer", TenantCreatedEventValidator.CONSUMER_NAME)
                .tag("result", "ignored")
                .register(meterRegistry);
    }

    /** 合法 ignored 事件不建立去重状态；成功写入或 ignored 计数完成后才确认。 */
    @KafkaListener(
            topics = "${saasforge.audit.tenant-access-topic}",
            groupId = TenantCreatedEventValidator.CONSUMER_NAME)
    public void consume(ConsumerRecord<String, String> message, Acknowledgment acknowledgment) {
        var record = validator.validate(
                message.topic(), message.key(), TenantCreatedEventValidator.CONSUMER_NAME, message.value());
        if (record.isPresent()) {
            service.record(TenantCreatedEventValidator.CONSUMER_NAME, record.orElseThrow());
        } else {
            ignored.increment();
        }
        acknowledgment.acknowledge();
    }
}
