package io.saasforge.starter.security;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.contracts.route.HttpRouteCatalogLoader;
import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

/** 为 Servlet 接收端装配共享 Catalog 驱动的 User/Service Token 复验边界。 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SaasForgeHttpAuthenticationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    HttpRouteCatalog saasForgeHttpRouteCatalog() {
        return HttpRouteCatalogLoader.load();
    }

    @Bean
    ReceiverRouteCatalog saasForgeReceiverRouteCatalog(HttpRouteCatalog catalog, Environment environment) {
        return new ReceiverRouteCatalog(catalog, environment.getProperty("spring.application.name"));
    }

    @Bean
    ReceiverTokenAuthenticators saasForgeReceiverTokenAuthenticators(
            UserAccessTokenSignatureVerifier userSignatures,
            UserAccessTokenContextRevocationChecker userRevocations,
            ServiceAccessTokenSignatureVerifier serviceSignatures,
            ServiceAccessTokenRevocationChecker serviceRevocations) {
        return new ReceiverTokenAuthenticators(
                userSignatures::verify, userRevocations, serviceSignatures::verify, serviceRevocations);
    }

    @Bean
    ReceiverProblemDetailsWriter saasForgeReceiverProblemDetailsWriter(ObjectMapper objectMapper) {
        return new ReceiverProblemDetailsWriter(objectMapper);
    }

    @Bean
    FilterRegistrationBean<HttpReceiverAuthenticationFilter> saasForgeHttpReceiverAuthenticationFilter(
            ReceiverRouteCatalog catalog,
            ReceiverTokenAuthenticators authenticators,
            ReceiverProblemDetailsWriter problems) {
        var registration = new FilterRegistrationBean<>(
                new HttpReceiverAuthenticationFilter(catalog, authenticators, problems));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
