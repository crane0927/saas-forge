package io.saasforge.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;

class NacosRegistrationReadinessHealthIndicatorTest {

    @Test
    void remainsNotReadyUntilNacosConfirmsRegistration() {
        NacosRegistrationReadinessHealthIndicator indicator = new NacosRegistrationReadinessHealthIndicator();

        assertEquals(Status.DOWN, indicator.health().getStatus());

        indicator.onApplicationEvent(new InstanceRegisteredEvent<>(this, "nacos"));

        assertEquals(Status.UP, indicator.health().getStatus());
    }
}
