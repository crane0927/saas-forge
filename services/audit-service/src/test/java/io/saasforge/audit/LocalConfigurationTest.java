package io.saasforge.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.audit.config.RequiredNacosConfiguration;
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
    void loadsPersonalConfigurationWithoutConfigCenterAndKeepsConsumerReadiness() throws Exception {
        Files.copy(Path.of("src/main/resources/application.yaml"), directory.resolve("application.yaml"));
        Files.copy(Path.of("src/main/resources/application-local.yaml.example"), directory.resolve("application-local.yaml"));
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RequiredNacosConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "spring.config.location=" + directory.toUri(),
                        "NACOS_SERVER_ADDR=127.0.0.1:1",
                        "NACOS_AUDIT_USERNAME=test",
                        "NACOS_AUDIT_PASSWORD=test",
                        "AUDIT_DATABASE_URL=jdbc:postgresql://database.example:5544/audit_db",
                        "AUDIT_DATABASE_PASSWORD=test",
                        "KAFKA_BOOTSTRAP_SERVERS=broker.example:39092",
                        "AUDIT_HTTP_PORT=8184",
                        "AUDIT_REGISTER_IP=192.0.2.4")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.config.import", "")).doesNotContain("nacos:");
                    assertThat(environment.getProperty("spring.cloud.nacos.config.enabled")).isEqualTo("false");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.enabled")).isEqualTo("true");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.ip")).isEqualTo("192.0.2.4");
                    assertThat(environment.getProperty("spring.cloud.nacos.discovery.port")).isEqualTo("8184");
                    assertThat(environment.getProperty("server.port")).isEqualTo("8184");
                    assertThat(environment.getProperty("saasforge.audit.configuration-revision")).isEqualTo("local");
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
}
