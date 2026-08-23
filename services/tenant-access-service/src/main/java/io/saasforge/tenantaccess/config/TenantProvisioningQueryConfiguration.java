package io.saasforge.tenantaccess.config;

import io.saasforge.sdk.auth.ServiceAccessTokenVerifier;
import io.saasforge.tenantaccess.application.tenant.InitialSubscriptionEligibilityService;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.infrastructure.security.IamJwksKeyResolver;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class TenantProvisioningQueryConfiguration {
    @Bean
    InitialSubscriptionEligibilityService initialSubscriptionEligibilityService(
            TenantRepository tenants, Clock clock) {
        return new InitialSubscriptionEligibilityService(tenants, clock);
    }

    @Bean
    ServiceAccessTokenVerifier tenantAccessServiceAccessTokenVerifier(
            RestClient tenantAccessIamRestClient,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenVerifier(
                new IamJwksKeyResolver(tenantAccessIamRestClient),
                clock, issuer, "saasforge-api", Duration.ofSeconds(30));
    }
}
