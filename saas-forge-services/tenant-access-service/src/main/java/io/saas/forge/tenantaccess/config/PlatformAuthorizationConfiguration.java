package io.saas.forge.tenantaccess.config;

import io.saas.forge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saas.forge.sdk.auth.PlatformRequestAuthorizer;
import io.saas.forge.sdk.auth.UserAccessTokenVerifier;
import io.saas.forge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saas.forge.tenantaccess.infrastructure.grpc.GrpcPlatformRoleChecker;
import io.saas.forge.tenantaccess.infrastructure.security.IamJwksKeyResolver;
import io.saas.forge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import io.saas.forge.tenantaccess.infrastructure.security.RedisUserAccessTokenRevocationChecker;
import io.saas.forge.tenantaccess.infrastructure.security.SdkPlatformAdminAuthorizer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

@Configuration
public class PlatformAuthorizationConfiguration {
    @Bean
    IamServiceAccessTokenProvider tenantAccessIamServiceAccessTokenProvider(
            RestClient tenantAccessIamRestClient,
            Clock clock,
            @Value("${saas.forge.tenant-access.service-client-id-file}") String clientIdFile,
            @Value("${saas.forge.tenant-access.service-client-secret-file}") String clientSecretFile) {
        return new IamServiceAccessTokenProvider(
                tenantAccessIamRestClient, Path.of(clientIdFile), Path.of(clientSecretFile), clock);
    }

    @Bean
    PlatformAdminAuthorizer platformAdminAuthorizer(
            RestClient tenantAccessIamRestClient,
            IamServiceAccessTokenProvider serviceTokens,
            StringRedisTemplate redis,
            @Qualifier("iamServiceChannel") Channel iamChannel,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${saas.forge.environment:dev}") String environment) {
        IamJwksKeyResolver keys = new IamJwksKeyResolver(tenantAccessIamRestClient);
        RedisUserAccessTokenRevocationChecker revocations =
                new RedisUserAccessTokenRevocationChecker(redis, environment);
        UserAccessTokenVerifier userTokens = new UserAccessTokenVerifier(
                keys, revocations, clock, issuer, "saas.forge-api", Duration.ofSeconds(30));
        GrpcPlatformRoleChecker roles = new GrpcPlatformRoleChecker(
                PlatformAuthorizationServiceGrpc.newBlockingStub(iamChannel),
                serviceTokens::token);
        return new SdkPlatformAdminAuthorizer(new PlatformRequestAuthorizer(userTokens, roles));
    }
}
