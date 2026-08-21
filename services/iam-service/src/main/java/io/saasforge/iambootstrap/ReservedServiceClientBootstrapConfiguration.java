package io.saasforge.iambootstrap;

import io.saasforge.iam.application.bootstrap.ReservedServiceClientBootstrapService;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReservedServiceClientBootstrapConfiguration {
    @Bean
    Clock reservedClientBootstrapClock() {
        return Clock.systemUTC();
    }

    @Bean
    ReservedServiceClientBootstrapService reservedServiceClientBootstrapService(
            OAuthClientRepository clients, Clock reservedClientBootstrapClock) {
        return new ReservedServiceClientBootstrapService(clients, reservedClientBootstrapClock);
    }

    @Bean
    SecretTextFileReader reservedClientSecretTextFileReader() {
        return new SecretTextFileReader();
    }

    @Bean
    ReservedServiceClientBootstrapRunner reservedServiceClientBootstrapRunner(
            ReservedServiceClientBootstrapService service,
            SecretTextFileReader reader,
            @Value("${saasforge.iam.bootstrap.service-clients.iam.id-file}") Path iamIdFile,
            @Value("${saasforge.iam.bootstrap.service-clients.iam.secret-file}") Path iamSecretFile,
            @Value("${saasforge.iam.bootstrap.service-clients.tenant-access.id-file}") Path tenantAccessIdFile,
            @Value("${saasforge.iam.bootstrap.service-clients.tenant-access.secret-file}") Path tenantAccessSecretFile,
            @Value("${saasforge.iam.bootstrap.service-clients.entitlement.id-file}") Path entitlementIdFile,
            @Value("${saasforge.iam.bootstrap.service-clients.entitlement.secret-file}") Path entitlementSecretFile) {
        return new ReservedServiceClientBootstrapRunner(
                service, reader,
                iamIdFile, iamSecretFile,
                tenantAccessIdFile, tenantAccessSecretFile,
                entitlementIdFile, entitlementSecretFile);
    }
}
