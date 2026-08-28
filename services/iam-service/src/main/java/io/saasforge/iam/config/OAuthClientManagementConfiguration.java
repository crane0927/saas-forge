package io.saasforge.iam.config;

import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.application.authentication.UuidV7Generator;
import io.saasforge.iam.application.client.ClientSecretIssuer;
import io.saasforge.iam.application.client.OAuthClientCreatedEventFactory;
import io.saasforge.iam.application.client.OAuthClientManagementAuthorizer;
import io.saasforge.iam.application.client.OAuthClientManagementService;
import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import io.saasforge.iam.domain.client.OAuthClientManagementOperationRepository;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.outbox.OutboxEventRepository;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.infrastructure.security.IamJwtVerificationKeyResolver;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class OAuthClientManagementConfiguration {
    @Bean
    ClientSecretIssuer clientSecretIssuer(SecureRandom authenticationSecureRandom) {
        return new ClientSecretIssuer(authenticationSecureRandom);
    }

    @Bean
    OAuthClientCreatedEventFactory oauthClientCreatedEventFactory(
            ObjectMapper objectMapper,
            UuidV7Generator ids,
            @Value("${saasforge.environment:dev}") String environment) {
        return new OAuthClientCreatedEventFactory(objectMapper, ids, environment);
    }

    @Bean
    OAuthClientManagementService oauthClientManagementService(
            OAuthClientRepository clients,
            OAuthClientManagementOperationRepository operations,
            OutboxEventRepository outbox,
            OAuthClientCreatedEventFactory events,
            ClientSecretIssuer secrets,
            UuidV7Generator ids,
            Clock clock) {
        return new OAuthClientManagementService(clients, operations, outbox, events, secrets, ids, clock);
    }

    @Bean
    OAuthClientManagementAuthorizer oauthClientManagementAuthorizer(
            SigningKeyRepository signingKeys,
            RevocationIndex revocations,
            PlatformRoleAssignmentRepository roles,
            Clock clock,
            @Value("${security.jwt.issuer}") String issuer) {
        UserAccessTokenSignatureVerifier signatures = new UserAccessTokenSignatureVerifier(
                new IamJwtVerificationKeyResolver(signingKeys), clock, issuer,
                "saasforge-api", Duration.ofSeconds(30));
        return new OAuthClientManagementAuthorizer(signatures, revocations, roles, clock);
    }
}
