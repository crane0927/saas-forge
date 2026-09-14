package io.saas.forge.starter.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.saas.forge.contracts.route.HttpRouteCatalog;
import io.saas.forge.sdk.auth.IdentityContextAccessor;
import io.saas.forge.sdk.auth.ServiceJwtVerificationKeyResolver;
import io.saas.forge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saas.forge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saas.forge.sdk.auth.ServiceContextAccessor;
import io.saas.forge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saas.forge.sdk.tenant.TenantContextAccessor;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import tools.jackson.databind.ObjectMapper;

class SaasForgeHttpAuthenticationAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = baseContextRunner()
            .withBean(UserAccessTokenSignatureVerifier.class,
                    SaasForgeHttpAuthenticationAutoConfigurationTest::userSignatures)
            .withBean(ServiceAccessTokenSignatureVerifier.class,
                    SaasForgeHttpAuthenticationAutoConfigurationTest::serviceSignatures)
            .withBean(UserAccessTokenContextRevocationChecker.class,
                    () -> (jti, kid, membershipId, tenantId) -> false)
            .withBean(ServiceAccessTokenRevocationChecker.class,
                    () -> (clientId, kid) -> false);

    private static WebApplicationContextRunner baseContextRunner() {
        return new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SaasForgeHttpAuthenticationAutoConfiguration.class))
            .withPropertyValues("spring.application.name=receiver-service")
            .withBean(HttpRouteCatalog.class, SaasForgeHttpAuthenticationAutoConfigurationTest::catalog)
            .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void startsWithoutConsumerAuthenticationAdaptersWhenDependenciesAreTemporarilyUnavailable() {
        baseContextRunner()
                .withPropertyValues("security.jwt.issuer=https://iam.saas.forge.test", "saas.forge.environment=test")
                .withBean(org.springframework.cloud.client.loadbalancer.LoadBalancerClient.class,
                        () -> org.mockito.Mockito.mock(org.springframework.cloud.client.loadbalancer.LoadBalancerClient.class))
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void readinessIsDownWhenAuthenticationDependenciesAreUnavailable() {
        baseContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration.class,
                        org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration.class))
                .withPropertyValues("security.jwt.issuer=issuer", "saas.forge.environment=test")
                .withBean(org.springframework.cloud.client.loadbalancer.LoadBalancerClient.class,
                        () -> org.mockito.Mockito.mock(org.springframework.cloud.client.loadbalancer.LoadBalancerClient.class))
                .withBean(org.springframework.data.redis.core.StringRedisTemplate.class,
                        () -> org.mockito.Mockito.mock(org.springframework.data.redis.core.StringRedisTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var health = context.getBean(org.springframework.boot.health.actuate.endpoint.HealthEndpoint.class)
                            .healthForPath("readiness");
                    assertThat(health).isNotNull();
                    assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
                });
    }

    @Test
    void registersTheCatalogBoundAuthenticationFilter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReceiverRouteCatalog.class);
            assertThat(context).hasSingleBean(ReceiverTokenAuthenticators.class);
            assertThat(context).hasSingleBean(IdentityContextAccessor.class);
            assertThat(context).hasSingleBean(ServiceContextAccessor.class);
            assertThat(context).hasSingleBean(TenantContextAccessor.class);
            FilterRegistrationBean<?> registration = context.getBean(
                    "saasForgeHttpReceiverAuthenticationFilter", FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(HttpReceiverAuthenticationFilter.class);
        });
    }

    @Test
    void failsApplicationStartupWhenServiceOwnershipDoesNotMatch() {
        contextRunner.withPropertyValues("spring.application.name=other-service").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "当前服务与 HTTP Route Catalog 路由归属不匹配: other-service");
        });
    }

    @Test
    void failsApplicationStartupWithActionableDiagnosticsForEveryMissingAuthenticationAdapter() {
        assertMissing(baseContextRunner()
                        .withBean(ServiceAccessTokenSignatureVerifier.class,
                                SaasForgeHttpAuthenticationAutoConfigurationTest::serviceSignatures)
                        .withBean(UserAccessTokenContextRevocationChecker.class,
                                () -> (jti, kid, membershipId, tenantId) -> false)
                        .withBean(ServiceAccessTokenRevocationChecker.class,
                                () -> (clientId, kid) -> false),
                UserAccessTokenSignatureVerifier.class);

        assertMissing(baseContextRunner()
                        .withBean(UserAccessTokenSignatureVerifier.class,
                                SaasForgeHttpAuthenticationAutoConfigurationTest::userSignatures)
                        .withBean(UserAccessTokenContextRevocationChecker.class,
                                () -> (jti, kid, membershipId, tenantId) -> false)
                        .withBean(ServiceAccessTokenRevocationChecker.class,
                                () -> (clientId, kid) -> false),
                ServiceAccessTokenSignatureVerifier.class);

        assertMissing(baseContextRunner()
                        .withBean(UserAccessTokenSignatureVerifier.class,
                                SaasForgeHttpAuthenticationAutoConfigurationTest::userSignatures)
                        .withBean(ServiceAccessTokenSignatureVerifier.class,
                                SaasForgeHttpAuthenticationAutoConfigurationTest::serviceSignatures)
                        .withBean(ServiceAccessTokenRevocationChecker.class,
                                () -> (clientId, kid) -> false),
                UserAccessTokenContextRevocationChecker.class);

        assertMissing(baseContextRunner()
                        .withBean(UserAccessTokenSignatureVerifier.class,
                                SaasForgeHttpAuthenticationAutoConfigurationTest::userSignatures)
                        .withBean(ServiceAccessTokenSignatureVerifier.class,
                                SaasForgeHttpAuthenticationAutoConfigurationTest::serviceSignatures)
                        .withBean(UserAccessTokenContextRevocationChecker.class,
                                () -> (jti, kid, membershipId, tenantId) -> false),
                ServiceAccessTokenRevocationChecker.class);
    }

    private static void assertMissing(WebApplicationContextRunner runner, Class<?> adapterType) {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "缺少必需认证适配器: " + adapterType.getName());
        });
    }

    private static HttpRouteCatalog catalog() {
        return new HttpRouteCatalog(1, List.of(new HttpRouteCatalog.Route(
                "readUser",
                HttpRouteCatalog.HttpMethod.GET,
                "/api/user",
                "receiver-service",
                HttpRouteCatalog.CredentialRequirement.USER_REQUIRED,
                List.of())));
    }

    private static UserAccessTokenSignatureVerifier userSignatures() {
        return new UserAccessTokenSignatureVerifier(
                missingKeys(), Clock.systemUTC(), "issuer", "audience", Duration.ZERO);
    }

    private static ServiceAccessTokenSignatureVerifier serviceSignatures() {
        return new ServiceAccessTokenSignatureVerifier(
                missingKeys(), Clock.systemUTC(), "issuer", "audience", Duration.ZERO);
    }

    private static ServiceJwtVerificationKeyResolver missingKeys() {
        return kid -> Optional.empty();
    }
}
