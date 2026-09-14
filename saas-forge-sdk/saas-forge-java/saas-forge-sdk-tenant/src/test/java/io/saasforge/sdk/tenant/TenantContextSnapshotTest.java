package io.saasforge.sdk.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantContextSnapshotTest {

    private static final UUID IDENTITY_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdef");
    private static final UUID MEMBERSHIP_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdea");
    private static final UUID TENANT_ID = UUID.fromString("018f5f2a-7b3c-7def-8123-456789abcdeb");

    @Test
    void exposesOneAtomicTenantContextSnapshot() {
        TenantContextSnapshot context = new TenantContextSnapshot(IDENTITY_ID, MEMBERSHIP_ID, TENANT_ID);

        assertEquals(IDENTITY_ID, context.identityId());
        assertEquals(MEMBERSHIP_ID, context.membershipId());
        assertEquals(TENANT_ID, context.tenantId());
    }

    @Test
    void rejectsAnIncompleteTenantContextSnapshot() {
        assertThrows(IllegalArgumentException.class,
                () -> new TenantContextSnapshot(null, MEMBERSHIP_ID, TENANT_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantContextSnapshot(IDENTITY_ID, null, TENANT_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new TenantContextSnapshot(IDENTITY_ID, MEMBERSHIP_ID, null));
    }
}
