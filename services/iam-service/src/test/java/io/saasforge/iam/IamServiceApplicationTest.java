package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.config.NacosRegistrationReadinessHealthIndicator;
import io.saasforge.iam.config.RequiredNacosConfiguration;
import io.saasforge.iambootstrap.PlatformAdminBootstrapApplication;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;

class NacosRegistrationReadinessHealthIndicatorTest {

    @Test
    void normalStartupDoesNotScanOrConfigurePlatformAdminBootstrapSecrets() throws Exception {
        SpringBootApplication application = IamServiceApplication.class.getAnnotation(SpringBootApplication.class);
        assertEquals(0, application.scanBasePackages().length);
        assertFalse(PlatformAdminBootstrapApplication.class.getPackageName()
                .startsWith(IamServiceApplication.class.getPackageName() + "."));

        try (InputStream input = IamServiceApplication.class.getResourceAsStream("/application.yaml")) {
            assertNotNull(input);
            String runtimeConfiguration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertFalse(runtimeConfiguration.contains("platform-admin"));
            assertFalse(runtimeConfiguration.contains("bootstrap.platform-admin"));
        }
    }

    @Test
    void createsRequiredNacosConfigurationWhenTheMarkerIsProvided() {
        new ApplicationContextRunner()
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues("saasforge.iam.configuration-revision=test")
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
        assertThrows(RuntimeException.class, () -> new SpringApplicationBuilder(IamServiceApplication.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.config.import=nacos:iam-service.yaml?group=SAAS_FORGE",
                        "spring.cloud.nacos.config.server-addr=127.0.0.1:1",
                        "spring.cloud.nacos.username=iam-test",
                        "spring.cloud.nacos.password=iam-test-password",
                        "spring.cloud.nacos.discovery.enabled=false")
                .run());
    }
}
