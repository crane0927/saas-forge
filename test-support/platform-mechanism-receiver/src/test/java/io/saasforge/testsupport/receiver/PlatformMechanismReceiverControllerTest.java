package io.saasforge.testsupport.receiver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.saasforge.sdk.auth.ServiceContext;
import io.saasforge.sdk.auth.ServiceContextAccessor;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlatformMechanismReceiverControllerTest {

    @Test
    void returnsOnlyVerifiedServiceContextAndInstanceIdentity() {
        UUID clientId = UUID.fromString("019c04cf-4c00-7000-8000-000000000001");
        ServiceContextAccessor serviceContexts = () -> Optional.of(new ServiceContext(
                clientId, Set.of("runtime:quota:write", "runtime:read")));

        PlatformMechanismReceiverController.Response response =
                new PlatformMechanismReceiverController("receiver-1", serviceContexts).accept();

        assertEquals(clientId, response.clientId());
        assertEquals(Set.of("runtime:read", "runtime:quota:write"), response.scopes());
        assertEquals("receiver-1", response.instanceId());
    }
}
