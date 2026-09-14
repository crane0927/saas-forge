package io.saasforge.acceptance.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalConsumerDependencyBoundaryTest {

    private static final List<String> FORBIDDEN_CLASSPATH_FRAGMENTS = List.of(
            "saas-forge-sdk-permission",
            "saas-forge-sdk-feature",
            "saas-forge-sdk-quota",
            "saas-forge-sdk-audit",
            "saas-forge-protobuf-contracts",
            "protobuf-java",
            "grpc-",
            "mybatis",
            "flyway",
            "/gateway/target/",
            "/saas-forge-services/iam-service/target/",
            "/saas-forge-services/tenant-access-service/target/",
            "/saas-forge-services/entitlement-service/target/",
            "/saas-forge-services/audit-service/target/");

    @Test
    void runtimeClasspathDoesNotContainFutureSdksOrInternalPlatformImplementations() {
        String classpath = System.getProperty("java.class.path").replace(File.separatorChar, '/');

        FORBIDDEN_CLASSPATH_FRAGMENTS.forEach(fragment ->
                assertThat(classpath).doesNotContain(fragment));
    }
}
