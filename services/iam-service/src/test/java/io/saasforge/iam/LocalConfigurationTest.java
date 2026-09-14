package io.saasforge.iam;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.iam.config.RequiredNacosConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LocalConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void loadsPersonalConfigurationWithoutConfigCenterAndKeepsDiscovery() throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        var secrets = Files.createDirectory(directory.resolve("secrets"));
        Files.writeString(secrets.resolve("IAM_APP_PASSWORD"), "test-secret");
        var personalConfiguration = directory.resolve("application-local-file.yaml");
        Files.copy(Path.of("../../deploy/nacos/dev/iam-service.yaml"), personalConfiguration);
        Files.writeString(personalConfiguration, """

                ---
                spring.cloud.nacos.config.enabled: "false"
                spring.cloud.nacos.config.import-check.enabled: "false"
                spring.cloud.nacos.discovery.enabled: "true"
                saasforge.iam.configuration-revision: "local"
                """, StandardOpenOption.APPEND);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues(
                        "SAASFORGE_SECRETS_IMPORT=configtree:" + secrets + "/",
                        "IAM_HTTP_PORT=8181",
                        "IAM_GRPC_PORT=9091",
                        "spring.profiles.active=local-file",
                        "spring.config.location=" + directory.toUri(),
                        "NACOS_SERVER_ADDR=127.0.0.1:1",
                        "NACOS_IAM_USERNAME=test",
                        "NACOS_IAM_PASSWORD=test",
                        "IAM_JWT_ISSUER=https://issuer.saasforge.test",
                        "BROWSER_ROOT_DOMAIN=saasforge.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("test-secret");
                    assertThat(environment.getProperty("server.port")).isEqualTo("8181");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.port")).isEqualTo("8181");
                    assertThat(environment.getProperty("spring.cloud.nacos.config.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled")).isEqualTo("true");
                    assertThat(environment.getProperty("saasforge.iam.configuration-revision")).isEqualTo("local");
                    assertThat(environment.getProperty("security.jwt.issuer")).isEqualTo("https://issuer.saasforge.test");
                    assertThat(environment.getProperty("browser.rootDomain")).isEqualTo("saasforge.test");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.metadata.grpc.port")).isEqualTo("9091");
                    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.config.import", "")).doesNotContain("nacos:");
                });
    }

    @Test
    void refusesDisablingNacosWithoutExplicitLocalFileProfile() throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues(
                        "spring.config.location=" + directory.toUri(),
                        "SAASFORGE_SECRETS_IMPORT=",
                        "spring.cloud.nacos.config.server-addr=127.0.0.1:1",
                        "spring.cloud.nacos.username=test",
                        "spring.cloud.nacos.password=test",
                        "spring.cloud.nacos.config.enabled=false",
                        "saasforge.iam.configuration-revision=stale-local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("nacos:iam-service.yaml?group=SAAS_FORGE&refreshEnabled=false");
                });
    }
}
