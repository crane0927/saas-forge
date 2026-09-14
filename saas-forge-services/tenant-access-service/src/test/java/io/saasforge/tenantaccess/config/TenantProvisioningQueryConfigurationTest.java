package io.saasforge.tenantaccess.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.tenantaccess.domain.tenant.Tenant;
import io.saasforge.tenantaccess.domain.tenant.TenantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

class TenantProvisioningQueryConfigurationTest {
    private static final UUID IAM_SERVICE_CLIENT_ID =
            UUID.fromString("019535d9-0001-7000-8000-000000000001");

    @TempDir
    Path directory;

    private final TenantProvisioningQueryConfiguration configuration =
            new TenantProvisioningQueryConfiguration();

    @Test
    void wiresEligibilityAndTokenAuthorization() {
        assertNotNull(configuration.initialSubscriptionEligibilityService(
                emptyTenantRepository(), Clock.systemUTC()));
        ServiceAccessTokenSignatureVerifier signatures =
                configuration.tenantAccessServiceAccessTokenSignatureVerifier(
                        RestClient.create(), Clock.systemUTC(), "https://iam.test.saasforge.invalid");
        ServiceAccessTokenRevocationChecker revocations =
                configuration.tenantAccessServiceAccessTokenRevocationChecker(
                        org.mockito.Mockito.mock(StringRedisTemplate.class), "test");
        assertNotNull(configuration.tenantAccessServiceAccessTokenAuthorizer(signatures, revocations));
    }

    @Test
    void readsCanonicalIamServiceClientIdFromSecretFile() throws Exception {
        Path clientIdFile = Files.writeString(directory.resolve("iam-client-id"), IAM_SERVICE_CLIENT_ID + "\n");

        assertEquals(IAM_SERVICE_CLIENT_ID, configuration.iamServiceClientId(clientIdFile.toString()).value());
    }

    @Test
    void rejectsMissingMalformedAndNonV7ClientIdFiles() throws Exception {
        Path malformed = Files.writeString(directory.resolve("malformed-client-id"), "not-a-uuid");
        Path nonV7 = Files.writeString(directory.resolve("non-v7-client-id"), UUID.randomUUID().toString());

        assertThrows(IllegalStateException.class,
                () -> configuration.iamServiceClientId(directory.resolve("missing-client-id").toString()));
        assertThrows(IllegalStateException.class,
                () -> configuration.iamServiceClientId(malformed.toString()));
        assertThrows(IllegalStateException.class,
                () -> configuration.iamServiceClientId(nonV7.toString()));
    }

    private static TenantRepository emptyTenantRepository() {
        return new TenantRepository() {
            @Override
            public void setOperationTarget(UUID tenantId) {}

            @Override
            public void create(Tenant tenant) {}

            @Override
            public Optional<Tenant> findById(UUID tenantId) {
                return Optional.empty();
            }
        };
    }
}
