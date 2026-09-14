package io.saasforge.acceptance.consumer;

import io.saasforge.sdk.auth.IdentityContext;
import io.saasforge.sdk.auth.IdentityContextAccessor;
import io.saasforge.sdk.tenant.TenantContextAccessor;
import io.saasforge.sdk.tenant.TenantContextSnapshot;
import io.saasforge.sdk.tenant.TenantContextUnavailableException;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ExternalConsumerContextController {

    private final IdentityContextAccessor identities;
    private final TenantContextAccessor tenants;

    ExternalConsumerContextController(
            IdentityContextAccessor identities,
            TenantContextAccessor tenants) {
        this.identities = identities;
        this.tenants = tenants;
    }

    @GetMapping("/__test/sdk-consumer/tenant-context")
    ContextResponse tenantContext() {
        IdentityContext identity = identities.current().orElseThrow();
        TenantContextSnapshot tenant = tenants.requireCurrent();
        return new ContextResponse(
                identity.identityId(),
                tenant.membershipId(),
                tenant.tenantId(),
                IdentityContext.class.isRecord(),
                TenantContextSnapshot.class.isRecord());
    }

    @GetMapping({
        "/__test/sdk-consumer/platform-context",
        "/__test/sdk-consumer/service-context",
        "/__test/sdk-consumer/anonymous-context"
    })
    UUID unavailableTenantContext() {
        return tenants.requireCurrent().tenantId();
    }

    @GetMapping("/__test/sdk-consumer/context-state")
    ContextState contextState() {
        boolean tenantAbsent;
        try {
            tenants.requireCurrent();
            tenantAbsent = false;
        } catch (TenantContextUnavailableException exception) {
            tenantAbsent = true;
        }
        return new ContextState(identities.current().isEmpty(), tenantAbsent);
    }

    record ContextResponse(
            UUID identityId,
            UUID membershipId,
            UUID tenantId,
            boolean identityImmutable,
            boolean tenantImmutable) {
    }

    record ContextState(boolean identityAbsent, boolean tenantAbsent) {
    }
}
