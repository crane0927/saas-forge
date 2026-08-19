package io.saasforge.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.entitlement.config.NacosRegistrationReadinessHealthIndicator;
import io.saasforge.entitlement.config.RequiredNacosConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;

class EntitlementServiceApplicationTest {

    @Test
    void createsRequiredNacosConfigurationWhenTheMarkerIsProvided() {
        new ApplicationContextRunner()
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues("saasforge.entitlement.configuration-revision=test")
                .run(context -> assertThat(context).hasSingleBean(RequiredNacosConfiguration.class));
    }

    @Test
    void remainsDownUntilTheServiceRegistryConfirmsRegistration() {
        NacosRegistrationReadinessHealthIndicator indicator = new NacosRegistrationReadinessHealthIndicator();

        assertEquals(Status.DOWN, indicator.health().getStatus());

        indicator.onApplicationEvent(new InstanceRegisteredEvent<>(this, null));

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void failsStartupWhenRequiredNacosConfigurationIsUnavailable() {
        assertThrows(RuntimeException.class, () -> new SpringApplicationBuilder(EntitlementServiceApplication.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.config.import=nacos:entitlement-service.yaml?group=SAAS_FORGE",
                        "spring.cloud.nacos.config.server-addr=127.0.0.1:1",
                        "spring.cloud.nacos.username=entitlement-test",
                        "spring.cloud.nacos.password=entitlement-test-password",
                        "spring.cloud.nacos.discovery.enabled=false")
                .run());
    }
}
