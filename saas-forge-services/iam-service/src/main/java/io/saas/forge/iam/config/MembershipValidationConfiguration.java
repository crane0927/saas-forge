package io.saas.forge.iam.config;

import io.saas.forge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saas.forge.iam.application.authentication.ClientCredentialsTokenService;
import io.saas.forge.iam.application.authentication.MembershipValidation;
import io.saas.forge.iam.infrastructure.grpc.GrpcMembershipValidation;
import io.saas.forge.iam.infrastructure.security.ReservedIamServiceAccessTokenProvider;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.grpc.Channel;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration(proxyBeanMethods = false)
public class MembershipValidationConfiguration {
    @Bean
    ReservedIamServiceAccessTokenProvider reservedIamServiceAccessTokenProvider(
            ClientCredentialsTokenService tokens,
            Clock clock,
            @Value("${saas.forge.iam.service-client-id-file}") String clientIdFile,
            @Value("${saas.forge.iam.service-client-secret-file}") String clientSecretFile) {
        return new ReservedIamServiceAccessTokenProvider(
                tokens, Path.of(clientIdFile), Path.of(clientSecretFile), clock);
    }

    @Bean
    @ConditionalOnMissingBean(MembershipValidation.class)
    MembershipValidation membershipValidation(
            @Qualifier("tenantAccessMembershipChannel") Channel membershipChannel,
            ReservedIamServiceAccessTokenProvider serviceTokens) {
        return new GrpcMembershipValidation(
                MembershipValidationServiceGrpc.newBlockingStub(membershipChannel),
                serviceTokens::membershipReadToken);
    }
}
