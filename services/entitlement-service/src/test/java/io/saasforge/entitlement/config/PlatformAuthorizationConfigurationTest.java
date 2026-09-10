package io.saasforge.entitlement.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import io.grpc.ManagedChannel;
import io.saasforge.entitlement.infrastructure.security.IamServiceAccessTokenProvider;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

class PlatformAuthorizationConfigurationTest {
    @Test
    void wiresPlatformAuthorizationAdapters() {
        PlatformAuthorizationConfiguration configuration =
                new PlatformAuthorizationConfiguration();

        assertNotNull(configuration.platformAdminAuthorizer(
                RestClient.create("http://iam"),
                mock(IamServiceAccessTokenProvider.class),
                mock(StringRedisTemplate.class),
                mock(ManagedChannel.class),
                Clock.systemUTC(),
                "https://iam.saasforge.test",
                "test"));
    }
}
