package io.saasforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.gateway.config.RequiredNacosConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void loadsPersonalConfigurationWithoutConfigCenterAndKeepsDiscovery() throws Exception {
        Files.copy(Path.of("config/application-local.yaml.example"), directory.resolve("application-local.yaml"));
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "spring.config.location=classpath:/application.yaml",
                        "spring.config.additional-location=" + directory.toUri(),
                        "NACOS_SERVER_ADDR=127.0.0.1:1",
                        "NACOS_GATEWAY_USERNAME=test",
                        "NACOS_GATEWAY_PASSWORD=test",
                        "IAM_JWT_ISSUER=https://issuer.saasforge.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.cloud.nacos.config.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled")).isEqualTo("true");
                    assertThat(environment.getProperty("saasforge.gateway.configuration-revision")).isEqualTo("local");
                    assertThat(environment.getProperty("security.jwt.issuer")).isEqualTo("https://issuer.saasforge.test");
                    assertThat(environment.getProperty("browser.rootDomain")).isEqualTo("saasforge.test");
                    assertThat(environment.getProperty("spring.config.import", "")).doesNotContain("nacos:");
                });
    }
}
