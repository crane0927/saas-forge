package io.saasforge.iam.config;

import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.iam.application.authentication.MembershipValidation;
import io.saasforge.iam.infrastructure.grpc.GrpcMembershipValidation;
import io.saasforge.iam.infrastructure.security.ReservedIamServiceAccessTokenProvider;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class MembershipValidationConfiguration {
    @Bean
    RestClient iamServiceRestClient(
            @Value("${saasforge.iam.http-base-url:http://iam-service:8080}") String baseUrl) {
        return RestClient.create(baseUrl);
    }

    @Bean
    ReservedIamServiceAccessTokenProvider reservedIamServiceAccessTokenProvider(
            RestClient iamServiceRestClient,
            Clock clock,
            @Value("${saasforge.iam.service-client-id-file}") String clientIdFile,
            @Value("${saasforge.iam.service-client-secret-file}") String clientSecretFile) {
        return new ReservedIamServiceAccessTokenProvider(
                iamServiceRestClient, Path.of(clientIdFile), Path.of(clientSecretFile), clock);
    }

    @Bean
    @ConditionalOnMissingBean(MembershipValidation.class)
    MembershipValidation membershipValidation(
            GrpcChannelFactory channels,
            ReservedIamServiceAccessTokenProvider serviceTokens,
            @Value("${saasforge.iam.tenant-access-grpc-target:tenant-access}") String target) {
        return new GrpcMembershipValidation(
                MembershipValidationServiceGrpc.newBlockingStub(channels.createChannel(target)),
                serviceTokens::membershipReadToken);
    }
}
