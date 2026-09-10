package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.config.RequiredNacosConfiguration;
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
                        "NACOS_IAM_USERNAME=test",
                        "NACOS_IAM_PASSWORD=test",
                        "IAM_JWT_ISSUER=https://issuer.saasforge.test",
                        "BROWSER_ROOT_DOMAIN=saasforge.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.cloud.nacos.config.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled")).isEqualTo("true");
                    assertThat(environment.getProperty("saasforge.iam.configuration-revision")).isEqualTo("local");
                    assertThat(environment.getProperty("security.jwt.issuer")).isEqualTo("https://issuer.saasforge.test");
                    assertThat(environment.getProperty("browser.rootDomain")).isEqualTo("saasforge.test");
                    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.config.import", "")).doesNotContain("nacos:");
                });
    }
}
