package io.saasforge.sdk.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class ReservedContextHeaderRegistryTest {

    @Test
    void recognizesEveryPlatformContextHeaderCaseInsensitively() {
        for (String name : List.of(
                "X-Identity",
                "X-Membership",
                "X-Tenant-Context",
                "X-Role",
                "X-Permission",
                "X-Scope",
                "X-Client")) {
            assertTrue(ReservedContextHeaderRegistry.contains(name));
            assertTrue(ReservedContextHeaderRegistry.contains(name.toLowerCase(Locale.ROOT)));
        }
    }

    @Test
    void doesNotReserveProtocolOrBusinessHeaders() {
        assertFalse(ReservedContextHeaderRegistry.contains(null));
        assertFalse(ReservedContextHeaderRegistry.contains("Authorization"));
        assertFalse(ReservedContextHeaderRegistry.contains("Cookie"));
        assertFalse(ReservedContextHeaderRegistry.contains("traceparent"));
        assertFalse(ReservedContextHeaderRegistry.contains("X-Request-Source"));
    }
}
