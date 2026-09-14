package io.saas.forge.iam.config;

import io.saas.forge.iam.application.signing.ActiveSigningKeyResolver;
import io.saas.forge.iam.application.signing.JwtSigningPort;
import io.saas.forge.iam.application.signing.JwtSigningService;
import io.saas.forge.iam.application.signing.SigningKeyLifecycleService;
import io.saas.forge.iam.application.signing.SigningKeyRevocationTransaction;
import io.saas.forge.iam.application.authentication.RevocationIndex;
import io.saas.forge.iam.domain.session.AccessTokenIssuanceRepository;
import io.saas.forge.iam.domain.signing.SigningKeyRepository;
import io.saas.forge.iam.infrastructure.signing.PemJcaJwtSigningAdapter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration(proxyBeanMethods = false)
public class JwtSigningConfiguration {

    @Bean
    ActiveSigningKeyResolver activeSigningKeyResolver(SigningKeyRepository repository) {
        return new ActiveSigningKeyResolver(repository);
    }

    @Bean
    JwtSigningService jwtSigningService(ActiveSigningKeyResolver resolver, JwtSigningPort signingPort) {
        return new JwtSigningService(resolver, signingPort);
    }

    @Bean
    JwtSigningStartupValidator jwtSigningStartupValidator(ActiveSigningKeyResolver resolver) {
        return new JwtSigningStartupValidator(resolver);
    }

    @Bean
    SigningKeyRevocationTransaction signingKeyRevocationTransaction(
            SigningKeyRepository signingKeys, AccessTokenIssuanceRepository issuances) {
        return new SigningKeyRevocationTransaction(signingKeys, issuances);
    }

    @Bean
    SigningKeyLifecycleService signingKeyLifecycleService(
            SigningKeyRepository signingKeys,
            AccessTokenIssuanceRepository issuances,
            RevocationIndex revocationIndex,
            SigningKeyRevocationTransaction transaction,
            Clock clock) {
        return new SigningKeyLifecycleService(signingKeys, issuances, revocationIndex, transaction, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "security.jwt.signing.adapter", havingValue = "pem-jca")
    JwtSigningPort pemJcaJwtSigningAdapter(
            @Value("${security.jwt.signing.pem.key-version-ref}") String keyVersionRef,
            @Value("${security.jwt.signing.pem.private-key-location}") Resource privateKeyResource) {
        return new PemJcaJwtSigningAdapter(keyVersionRef, privateKeyResource);
    }
}
