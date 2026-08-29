package io.saasforge.audit.infrastructure.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AuditConsumerTopology {
    private final String iamInputTopic;
    private final String tenantInputTopic;
    private final String iamIsolationTopic;
    private final String tenantIsolationTopic;

    public AuditConsumerTopology(
            @Value("${saasforge.audit.iam-session-topic}") String iamInputTopic,
            @Value("${saasforge.audit.tenant-access-topic}") String tenantInputTopic,
            @Value("${saasforge.audit.iam-session-isolation-topic}") String iamIsolationTopic,
            @Value("${saasforge.audit.tenant-isolation-topic}") String tenantIsolationTopic) {
        this.iamInputTopic = iamInputTopic;
        this.tenantInputTopic = tenantInputTopic;
        this.iamIsolationTopic = iamIsolationTopic;
        this.tenantIsolationTopic = tenantIsolationTopic;
        if (iamInputTopic.equals(tenantInputTopic) || iamIsolationTopic.equals(tenantIsolationTopic)) {
            throw new IllegalArgumentException("两个 Audit Consumer 的输入与隔离 Topic 必须相互独立");
        }
    }

    public String consumerName(String inputTopic) {
        if (iamInputTopic.equals(inputTopic)) {
            return SessionStartedEventValidator.CONSUMER_NAME;
        }
        if (tenantInputTopic.equals(inputTopic)) {
            return TenantCreatedEventValidator.CONSUMER_NAME;
        }
        throw new IllegalArgumentException("消息不属于已配置的 Audit Consumer Topic");
    }

    public String isolationTopic(String inputTopic) {
        if (iamInputTopic.equals(inputTopic)) {
            return iamIsolationTopic;
        }
        if (tenantInputTopic.equals(inputTopic)) {
            return tenantIsolationTopic;
        }
        throw new IllegalArgumentException("消息不属于已配置的 Audit Consumer Topic");
    }
}
