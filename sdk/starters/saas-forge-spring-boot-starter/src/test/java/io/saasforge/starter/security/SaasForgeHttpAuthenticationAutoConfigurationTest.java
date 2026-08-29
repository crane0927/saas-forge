package io.saasforge.starter.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
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

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SaasForgeHttpAuthenticationAutoConfiguration.class))
            .withPropertyValues("spring.application.name=receiver-service")
            .withBean(HttpRouteCatalog.class, SaasForgeHttpAuthenticationAutoConfigurationTest::catalog)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(UserAccessTokenSignatureVerifier.class,
                    SaasForgeHttpAuthenticationAutoConfigurationTest::userSignatures)
            .withBean(ServiceAccessTokenSignatureVerifier.class,
                    SaasForgeHttpAuthenticationAutoConfigurationTest::serviceSignatures)
            .withBean(UserAccessTokenContextRevocationChecker.class,
                    () -> (jti, kid, membershipId, tenantId) -> false)
            .withBean(ServiceAccessTokenRevocationChecker.class,
                    () -> (clientId, kid) -> false);

    @Test
    void registersTheCatalogBoundAuthenticationFilter() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReceiverRouteCatalog.class);
            assertThat(context).hasSingleBean(ReceiverTokenAuthenticators.class);
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
