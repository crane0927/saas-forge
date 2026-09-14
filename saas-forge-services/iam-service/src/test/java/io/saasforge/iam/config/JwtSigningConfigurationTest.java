package io.saasforge.iam.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.iam.application.signing.ActiveSigningKeyResolver;
import io.saasforge.iam.domain.signing.SigningKey;
import io.saasforge.iam.domain.signing.SigningKeyStatus;
import io.saasforge.iam.support.StubSigningKeyRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JwtSigningConfigurationTest {

    @Test
    void startupRequiresExactlyOneActiveKey() {
        StubSigningKeyRepository repository = new StubSigningKeyRepository();
        JwtSigningStartupValidator validator = new JwtSigningStartupValidator(new ActiveSigningKeyResolver(repository));

        repository.activeKeys(List.of());
        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);

        repository.activeKeys(List.of(activeKey("kid-1", "key/1"), activeKey("kid-2", "key/2")));
        assertThrows(IllegalStateException.class, validator::afterSingletonsInstantiated);

        repository.activeKeys(List.of(activeKey("kid-1", "key/1")));
        assertDoesNotThrow(validator::afterSingletonsInstantiated);
    }

    @Test
    void productionStyleExternalConfigurationCannotFallBackToAPemOrFakeAdapter() {
        StubSigningKeyRepository repository = new StubSigningKeyRepository();

        new ApplicationContextRunner()
                .withUserConfiguration(JwtSigningConfiguration.class)
                .withBean(io.saasforge.iam.domain.signing.SigningKeyRepository.class, () -> repository)
                .withPropertyValues("security.jwt.signing.adapter=external")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
                });
    }

    private static SigningKey activeKey(String kid, String keyVersionRef) {
        Instant publishedAt = Instant.parse("2026-08-20T00:00:00Z");
        return SigningKey.restore(
                UUID.randomUUID(), kid, keyVersionRef, "modulus", "AQAB", SigningKeyStatus.ACTIVE,
                publishedAt, publishedAt.plusSeconds(300), null, null, null);
    }
}
