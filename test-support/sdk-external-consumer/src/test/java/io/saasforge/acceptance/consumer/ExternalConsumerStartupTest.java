package io.saasforge.acceptance.consumer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saasforge.starter.security.UserAccessTokenContextRevocationChecker;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class ExternalConsumerStartupTest {

    @Test
    void applicationStartupFailsWithoutRequiredRevocationAdapter() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(
                        ExternalConsumerApplication.class,
                        MissingServiceRevocationAdapterConfiguration.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "spring.application.name=sdk-external-consumer-fixture",
                        "server.port=0",
                        "spring.main.banner-mode=off")
                .run())
                .hasRootCauseMessage(
                        "缺少必需认证适配器: io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker");
    }

    @Configuration(proxyBeanMethods = false)
    static class MissingServiceRevocationAdapterConfiguration {

        @Bean
        TestTokenAuthority testTokenAuthority() {
            return new TestTokenAuthority();
        }

        @Bean
        UserAccessTokenSignatureVerifier userAccessTokenSignatureVerifier(TestTokenAuthority tokens) {
            return new UserAccessTokenSignatureVerifier(
                    kid -> Optional.of(tokens.verificationKey()),
                    Clock.fixed(TestTokenAuthority.NOW, ZoneOffset.UTC),
                    TestTokenAuthority.ISSUER,
                    TestTokenAuthority.AUDIENCE,
                    Duration.ZERO);
        }

        @Bean
        UserAccessTokenContextRevocationChecker userAccessTokenContextRevocationChecker() {
            return (jti, kid, membershipId, tenantId) -> false;
        }

        @Bean
        ServiceAccessTokenSignatureVerifier serviceAccessTokenSignatureVerifier(TestTokenAuthority tokens) {
            return new ServiceAccessTokenSignatureVerifier(
                    kid -> Optional.of(tokens.verificationKey()),
                    Clock.fixed(TestTokenAuthority.NOW, ZoneOffset.UTC),
                    TestTokenAuthority.ISSUER,
                    TestTokenAuthority.AUDIENCE,
                    Duration.ZERO);
        }
    }
}
