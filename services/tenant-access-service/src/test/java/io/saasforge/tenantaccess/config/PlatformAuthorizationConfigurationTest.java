package io.saasforge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.ManagedChannel;
import io.saasforge.tenantaccess.infrastructure.security.IamServiceAccessTokenProvider;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

class PlatformAuthorizationConfigurationTest {
    @Test
    void wiresPlatformAuthorizationAdapters() {
        ManagedChannel channel = mock(ManagedChannel.class);
        PlatformAuthorizationConfiguration configuration =
                new PlatformAuthorizationConfiguration();

        assertNotNull(configuration.platformAdminAuthorizer(
                RestClient.create("http://iam"),
                mock(IamServiceAccessTokenProvider.class),
                mock(StringRedisTemplate.class),
                channel,
                Clock.systemUTC(),
                "https://iam.saasforge.test",
                "test"));
    }
}
