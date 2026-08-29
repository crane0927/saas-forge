package io.saasforge.starter.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationContextTest {

    @Test
    void userContextsEnforcePlatformOrTenantShape() {
        UUID identityId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        var platform = new UserAuthenticationContext(
                identityId, UserAuthenticationContext.ContextType.PLATFORM, null, null);
        var tenant = new UserAuthenticationContext(
                identityId, UserAuthenticationContext.ContextType.TENANT, membershipId, tenantId);

        assertEquals(identityId.toString(), platform.getName());
        assertEquals(tenantId, tenant.tenantId());
        assertThrows(IllegalArgumentException.class, () -> new UserAuthenticationContext(
                identityId, UserAuthenticationContext.ContextType.PLATFORM, membershipId, tenantId));
        assertThrows(IllegalArgumentException.class, () -> new UserAuthenticationContext(
                identityId, UserAuthenticationContext.ContextType.TENANT, membershipId, null));
    }

    @Test
    void serviceContextCopiesScopesAndHasNoUserTenantFields() {
        UUID clientId = UUID.randomUUID();
        Set<String> source = new HashSet<>(Set.of("runtime:write", "runtime:read"));

        ServiceAuthenticationContext context = new ServiceAuthenticationContext(clientId, source);
        source.clear();

        assertEquals(clientId.toString(), context.getName());
        assertEquals(Set.of("runtime:read", "runtime:write"), context.scopes());
        assertThrows(UnsupportedOperationException.class, () -> context.scopes().add("runtime:other"));
        assertThrows(IllegalArgumentException.class, () -> new ServiceAuthenticationContext(clientId, Set.of()));
    }
}
