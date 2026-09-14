package io.saas.forge.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.saas.forge.audit.config.RequiredNacosConfiguration;
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
    void loadsPersonalConfigurationWithoutConfigCenterAndKeepsConsumerReadiness() throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        var secrets = Files.createDirectory(directory.resolve("secrets"));
        Files.writeString(secrets.resolve("AUDIT_DATABASE_PASSWORD"), "test-secret");
        var personalConfiguration = directory.resolve("application-local-file.yaml");
        Files.copy(Path.of("../../deploy/nacos/dev/audit-service.yaml"), personalConfiguration);
        Files.writeString(personalConfiguration, """

                ---
                spring.cloud.nacos.config.enabled: "false"
                spring.cloud.nacos.config.import-check.enabled: "false"
                spring.cloud.nacos.discovery.enabled: "true"
                saas.forge.audit.configuration-revision: "local"
                """, StandardOpenOption.APPEND);
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues(
                        "SAASFORGE_SECRETS_IMPORT=configtree:" + secrets + "/",
                        "spring.profiles.active=local-file",
                        "spring.config.location=" + directory.toUri(),
                        "NACOS_SERVER_ADDR=127.0.0.1:1",
                        "NACOS_AUDIT_USERNAME=test",
                        "NACOS_AUDIT_PASSWORD=test",
                        "AUDIT_DATABASE_URL=jdbc:postgresql://database.example:5544/audit_db",
                        "KAFKA_BOOTSTRAP_SERVERS=broker.example:39092",
                        "AUDIT_HTTP_PORT=8184",
                        "AUDIT_REGISTER_IP=192.0.2.4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("test-secret");
                    assertThat(environment.getProperty("server.port")).isEqualTo("8184");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.port")).isEqualTo("8184");
                    assertThat(environment.getProperty("spring.config.import", "")).doesNotContain("nacos:");
                    assertThat(environment.getProperty("spring.cloud.nacos.config.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled")).isEqualTo("true");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.ip")).isEqualTo("192.0.2.4");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.port")).isEqualTo("8184");
                    assertThat(environment.getProperty("server.port")).isEqualTo("8184");
                    assertThat(environment.getProperty("saas.forge.audit.configuration-revision")).isEqualTo("local");
                    assertThat(environment.getProperty("spring.datasource.url"))
                            .isEqualTo("jdbc:postgresql://database.example:5544/audit_db");
                    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("audit_app");
                    assertThat(environment.getProperty("spring.kafka.bootstrap-servers")).isEqualTo("broker.example:39092");
                    assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.kafka.consumer.enable-auto-commit")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.kafka.listener.ack-mode")).isEqualTo("manual_immediate");
                    assertThat(environment.getProperty("management.endpoint.health.group.readiness.include"))
                            .isEqualTo("readinessState,nacosRegistrationReadiness,auditRuntimeReadiness");
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
                        "saas.forge.audit.configuration-revision=stale-local")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("nacos:audit-service.yaml?group=SAAS_FORGE&refreshEnabled=false");
                });
    }
}
