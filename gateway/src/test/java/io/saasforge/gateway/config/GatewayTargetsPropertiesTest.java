package io.saasforge.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.gateway.GatewayApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GatewayTargetsPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayApplication.class);

    @Test
    void startsWithAbsoluteHttpTargetsWithoutPathPrefixes() {
        contextRunner.withPropertyValues(
                "gateway.targets.iam=https://iam.internal",
                "gateway.targets.tenant-access=http://tenant-access.internal",
                "gateway.targets.entitlement=https://entitlement.internal/")
                .run(context -> assertThat(context.getStartupFailure()).isNull());
    }

    @Test
    void rejectsMissingTargets() {
        contextRunner.withPropertyValues(
                "gateway.targets.iam=https://iam.internal",
                "gateway.targets.tenant-access=http://tenant-access.internal")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }

    @Test
    void rejectsPathPrefixedTargets() {
        contextRunner.withPropertyValues(
                "gateway.targets.iam=https://iam.internal/api",
                "gateway.targets.tenant-access=http://tenant-access.internal",
                "gateway.targets.entitlement=https://entitlement.internal")
                .run(context -> assertThat(context.getStartupFailure()).isNotNull());
    }
}
