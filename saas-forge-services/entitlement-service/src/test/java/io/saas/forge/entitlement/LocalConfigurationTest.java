package io.saas.forge.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import io.saas.forge.entitlement.config.RequiredNacosConfiguration;
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
        Files.writeString(secrets.resolve("ENTITLEMENT_APP_PASSWORD"), "test-secret");
        var personalConfiguration = directory.resolve("application-local-file.yaml");
        Files.copy(Path.of("../../deploy/nacos/dev/entitlement-service.yaml"), personalConfiguration);
        Files.writeString(personalConfiguration, """

                ---
                spring.cloud.nacos.config.enabled: "false"
                spring.cloud.nacos.config.import-check.enabled: "false"
                spring.cloud.nacos.discovery.enabled: "true"
                saas.forge.entitlement.configuration-revision: "local"
                """, StandardOpenOption.APPEND);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues(
                        "SAASFORGE_SECRETS_IMPORT=configtree:" + secrets + "/",
                        "ENTITLEMENT_HTTP_PORT=8183",
                        "ENTITLEMENT_GRPC_PORT=9093",
                        "spring.profiles.active=local-file",
                        "spring.config.location=" + directory.toUri(),
                        "NACOS_SERVER_ADDR=127.0.0.1:1",
                        "NACOS_ENTITLEMENT_USERNAME=test",
                        "NACOS_ENTITLEMENT_PASSWORD=test",
                        "IAM_JWT_ISSUER=https://issuer.saas.forge.test",
                        "BROWSER_ROOT_DOMAIN=saas.forge.test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("test-secret");
                    assertThat(environment.getProperty("server.port")).isEqualTo("8183");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.port")).isEqualTo("8183");
                    assertThat(environment.getProperty("spring.cloud.nacos.config.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled")).isEqualTo("true");
                    assertThat(environment.getProperty("saas.forge.entitlement.configuration-revision")).isEqualTo("local");
                    assertThat(environment.getProperty("security.jwt.issuer")).isEqualTo("https://issuer.saas.forge.test");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.metadata.grpc.port")).isEqualTo("9093");
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
                        "saas.forge.entitlement.configuration-revision=stale-local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("nacos:entitlement-service.yaml?group=SAAS_FORGE&refreshEnabled=false");
                });
    }
}
