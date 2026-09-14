package io.saasforge.tenantaccess.config;

import io.saasforge.sdk.auth.ServiceAccessTokenAuthorizer;
import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.tenantaccess.application.tenant.InitialSubscriptionEligibilityService;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import io.saasforge.tenantaccess.infrastructure.security.IamJwksKeyResolver;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceClientId;
import io.saasforge.tenantaccess.infrastructure.security.RedisServiceAccessTokenRevocationChecker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

@Configuration
public class TenantProvisioningQueryConfiguration {
    @Bean
    InitialSubscriptionEligibilityService initialSubscriptionEligibilityService(
            TenantRepository tenants, Clock clock) {
        return new InitialSubscriptionEligibilityService(tenants, clock);
    }

    @Bean
    ServiceAccessTokenSignatureVerifier tenantAccessServiceAccessTokenSignatureVerifier(
            RestClient tenantAccessIamRestClient,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenSignatureVerifier(
                new IamJwksKeyResolver(tenantAccessIamRestClient),
                clock, issuer, "saasforge-api", Duration.ofSeconds(30));
    }

    @Bean
    ServiceAccessTokenRevocationChecker tenantAccessServiceAccessTokenRevocationChecker(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment) {
        return new RedisServiceAccessTokenRevocationChecker(redis, environment);
    }

    @Bean
    ServiceAccessTokenAuthorizer tenantAccessServiceAccessTokenAuthorizer(
            ServiceAccessTokenSignatureVerifier signatures,
            ServiceAccessTokenRevocationChecker revocations) {
        return new ServiceAccessTokenAuthorizer(signatures, revocations);
    }

    @Bean
    IamServiceClientId iamServiceClientId(
            @Value("${saasforge.tenant-access.iam-service-client-id-file}") String clientIdFile) {
        try {
            String value = Files.readString(Path.of(clientIdFile)).stripTrailing();
            UUID clientId = UUID.fromString(value);
            if (!clientId.toString().equals(value)) {
                throw new IllegalStateException("IAM Service Client ID 必须是规范 UUIDv7");
            }
            return new IamServiceClientId(clientId);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("IAM Service Client ID 文件不可读或内容不合法", exception);
        }
    }
}
