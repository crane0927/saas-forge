package io.saasforge.iam.config;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 只有服务注册表确认 IAM 注册成功后才允许实例对流量 Ready，避免配置已加载但未被发现的实例接收请求。
 */
@Component("nacosRegistrationReadiness")
public class NacosRegistrationReadinessHealthIndicator
        implements HealthIndicator, ApplicationListener<InstanceRegisteredEvent<?>> {

    private final AtomicBoolean registered = new AtomicBoolean();

    @Override
    public void onApplicationEvent(InstanceRegisteredEvent<?> event) {
        registered.set(true);
    }

    @Override
    public Health health() {
        if (registered.get()) {
            return Health.up().withDetail("registration", "completed").build();
        }
        return Health.down().withDetail("registration", "pending").build();
    }
}
