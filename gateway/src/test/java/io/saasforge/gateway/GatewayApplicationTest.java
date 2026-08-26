package io.saasforge.gateway;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.gateway.config.GatewayUserTokenTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "saasforge.gateway.configuration-revision=test"
})
@ActiveProfiles("gateway-test")
@Import(GatewayUserTokenTestConfiguration.class)
class GatewayApplicationTest {

    @Test
    void contextLoads() {
    }

    @Test
    void failsStartupWhenRequiredNacosConfigurationIsUnavailable() {
        assertThrows(RuntimeException.class, () -> new SpringApplicationBuilder(GatewayApplication.class)
                .properties(
                        "spring.main.web-application-type=none",
                        "spring.config.import=nacos:gateway.yaml?group=SAAS_FORGE",
                        "spring.cloud.nacos.config.server-addr=127.0.0.1:1",
                        "spring.cloud.nacos.username=gateway-test",
                        "spring.cloud.nacos.password=gateway-test-password",
                        "spring.cloud.nacos.discovery.enabled=false")
                .run());
    }
}
