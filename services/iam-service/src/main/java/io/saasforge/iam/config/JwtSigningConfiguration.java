package io.saasforge.iam.config;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import io.saasforge.iam.application.signing.JwtSigningPort;
import io.saasforge.iam.application.signing.JwtSigningService;
import io.saasforge.iam.domain.signing.SigningKeyRepository;
import io.saasforge.iam.infrastructure.signing.PemJcaJwtSigningAdapter;
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
    @ConditionalOnProperty(name = "security.jwt.signing.adapter", havingValue = "pem-jca")
    JwtSigningPort pemJcaJwtSigningAdapter(
            @Value("${security.jwt.signing.pem.key-version-ref}") String keyVersionRef,
            @Value("${security.jwt.signing.pem.private-key-location}") Resource privateKeyResource) {
        return new PemJcaJwtSigningAdapter(keyVersionRef, privateKeyResource);
    }
}
