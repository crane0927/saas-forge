package io.saasforge.iam.config;

import io.saasforge.contracts.tenantaccess.membership.v1.MembershipValidationServiceGrpc;
import io.saasforge.iam.application.authentication.ClientCredentialsTokenService;
import io.saasforge.iam.application.authentication.MembershipValidation;
import io.saasforge.iam.infrastructure.grpc.GrpcMembershipValidation;
import io.saasforge.iam.infrastructure.security.ReservedIamServiceAccessTokenProvider;
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
            @Value("${saasforge.iam.service-client-id-file}") String clientIdFile,
            @Value("${saasforge.iam.service-client-secret-file}") String clientSecretFile) {
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
