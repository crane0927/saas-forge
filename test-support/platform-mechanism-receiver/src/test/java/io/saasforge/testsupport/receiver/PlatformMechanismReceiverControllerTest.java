package io.saasforge.testsupport.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.saasforge.starter.security.ServiceAuthenticationContext;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PlatformMechanismReceiverControllerTest {

    @Test
    void returnsOnlyVerifiedServiceContextAndInstanceIdentity() {
        UUID clientId = UUID.fromString("019c04cf-4c00-7000-8000-000000000001");
        var principal = new ServiceAuthenticationContext(
                clientId, Set.of("runtime:quota:write", "runtime:read"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, Set.of());

        SecurityContextHolder.getContext().setAuthentication(authentication);
        PlatformMechanismReceiverController.Response response;
        try {
            response = new PlatformMechanismReceiverController("receiver-1").accept();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertEquals(clientId, response.clientId());
        assertEquals(Set.of("runtime:read", "runtime:quota:write"), response.scopes());
        assertEquals("receiver-1", response.instanceId());
    }
}
