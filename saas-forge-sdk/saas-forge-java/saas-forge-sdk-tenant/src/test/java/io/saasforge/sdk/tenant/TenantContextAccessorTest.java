package io.saasforge.sdk.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantContextAccessorTest {

    @Test
    void requiresTheCurrentTenantContextSnapshot() {
        TenantContextSnapshot expected = new TenantContextSnapshot(
                UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdef"),
                UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdea"),
                UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdeb"));
        TenantContextAccessor contexts = () -> expected;

        assertSame(expected, contexts.requireCurrent());
    }

    @Test
    void failsExplicitlyWhenTheCurrentTenantContextIsUnavailable() {
        TenantContextAccessor contexts = () -> {
            throw new TenantContextUnavailableException();
        };

        TenantContextUnavailableException exception =
                assertThrows(TenantContextUnavailableException.class, contexts::requireCurrent);

        assertEquals("Tenant Context is unavailable.", exception.getMessage());
    }
}
