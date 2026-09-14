package io.saasforge.sdk.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationContextTest {

    private static final UUID IDENTITY_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdef");
    private static final UUID CLIENT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdec");

    @Test
    void exposesOnlyImmutableVerifiedIdentityAndServiceValues() {
        IdentityContext identity = new IdentityContext(IDENTITY_ID);
        Set<String> sourceScopes = new HashSet<>(Set.of("runtime:write", "runtime:read"));
        ServiceContext service = new ServiceContext(CLIENT_ID, sourceScopes);
        sourceScopes.clear();

        assertEquals(IDENTITY_ID, identity.identityId());
        assertEquals(CLIENT_ID, service.clientId());
        assertEquals(Set.of("runtime:read", "runtime:write"), service.scopes());
        assertThrows(UnsupportedOperationException.class, () -> service.scopes().add("runtime:other"));
        assertThrows(IllegalArgumentException.class, () -> new IdentityContext(null));
        assertThrows(IllegalArgumentException.class, () -> new ServiceContext(null, Set.of("runtime:read")));
        assertThrows(IllegalArgumentException.class, () -> new ServiceContext(CLIENT_ID, Set.of()));
    }

    @Test
    void accessorsRepresentAnAbsentRequestContextWithoutWritableOperations() {
        IdentityContextAccessor identities = Optional::empty;
        ServiceContextAccessor services = Optional::empty;

        assertTrue(identities.current().isEmpty());
        assertTrue(services.current().isEmpty());
    }
}
