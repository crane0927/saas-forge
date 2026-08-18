package io.saasforge.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "gateway.targets.iam=http://127.0.0.1:18081",
        "gateway.targets.tenant-access=http://127.0.0.1:18082",
        "gateway.targets.entitlement=http://127.0.0.1:18083"
})
class GatewayApplicationTest {

    @Test
    void contextLoads() {
    }
}
