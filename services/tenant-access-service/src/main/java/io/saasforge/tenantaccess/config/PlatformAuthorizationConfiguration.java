package io.saasforge.tenantaccess.config;

import io.saasforge.contracts.iam.authorization.v1.PlatformAuthorizationServiceGrpc;
import io.saasforge.sdk.auth.GrpcPlatformRoleChecker;
import io.saasforge.sdk.auth.PlatformRequestAuthorizer;
import io.saasforge.sdk.auth.UserAccessTokenVerifier;
import io.saasforge.tenantaccess.application.authorization.PlatformAdminAuthorizer;
import io.saasforge.tenantaccess.infrastructure.security.IamJwksKeyResolver;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import io.saasforge.tenantaccess.infrastructure.security.RedisUserAccessTokenRevocationChecker;
import io.saasforge.tenantaccess.infrastructure.security.SdkPlatformAdminAuthorizer;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class PlatformAuthorizationConfiguration {
    @Bean
    RestClient tenantAccessIamRestClient(
            @Value("${saasforge.tenant-access.iam-http-base-url}") String baseUrl) {
        return RestClient.create(baseUrl);
    }

    @Bean
    IamServiceAccessTokenProvider tenantAccessIamServiceAccessTokenProvider(
            RestClient tenantAccessIamRestClient,
            Clock clock,
            @Value("${saasforge.tenant-access.service-client-id-file}") String clientIdFile,
            @Value("${saasforge.tenant-access.service-client-secret-file}") String clientSecretFile) {
        return new IamServiceAccessTokenProvider(
                tenantAccessIamRestClient, Path.of(clientIdFile), Path.of(clientSecretFile), clock);
    }

    @Bean
    PlatformAdminAuthorizer platformAdminAuthorizer(
            RestClient tenantAccessIamRestClient,
            IamServiceAccessTokenProvider serviceTokens,
            StringRedisTemplate redis,
            GrpcChannelFactory channels,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${saasforge.environment:dev}") String environment) {
        IamJwksKeyResolver keys = new IamJwksKeyResolver(tenantAccessIamRestClient);
        RedisUserAccessTokenRevocationChecker revocations =
                new RedisUserAccessTokenRevocationChecker(redis, environment);
        UserAccessTokenVerifier userTokens = new UserAccessTokenVerifier(
                keys, revocations, clock, issuer, "saasforge-api", Duration.ofSeconds(30));
        GrpcPlatformRoleChecker roles = new GrpcPlatformRoleChecker(
                PlatformAuthorizationServiceGrpc.newBlockingStub(channels.createChannel("iam")),
                serviceTokens::token);
        return new SdkPlatformAdminAuthorizer(new PlatformRequestAuthorizer(userTokens, roles));
    }
}
