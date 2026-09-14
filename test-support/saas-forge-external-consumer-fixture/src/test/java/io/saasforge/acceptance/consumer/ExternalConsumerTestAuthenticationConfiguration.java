package io.saasforge.acceptance.consumer;

import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saasforge.starter.security.UserAccessTokenContextRevocationChecker;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
class ExternalConsumerTestAuthenticationConfiguration {

    @Bean
    TestTokenAuthority testTokenAuthority() {
        return new TestTokenAuthority();
    }

    @Bean
    TestRevocationState testRevocationState() {
        return new TestRevocationState();
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
    UserAccessTokenContextRevocationChecker userAccessTokenContextRevocationChecker(TestRevocationState state) {
        return (jti, kid, membershipId, tenantId) -> {
            if (state.isUnavailable()) {
                throw new IllegalStateException("test revocation status unavailable");
            }
            return false;
        };
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

    @Bean
    ServiceAccessTokenRevocationChecker serviceAccessTokenRevocationChecker(TestRevocationState state) {
        return (clientId, kid) -> {
            if (state.isUnavailable()) {
                throw new IllegalStateException("test revocation status unavailable");
            }
            return false;
        };
    }
}
