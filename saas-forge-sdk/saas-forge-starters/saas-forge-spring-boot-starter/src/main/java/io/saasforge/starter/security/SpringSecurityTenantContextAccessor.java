package io.saasforge.starter.security;

import io.saasforge.sdk.tenant.TenantContextAccessor;
import io.saasforge.sdk.tenant.TenantContextSnapshot;
import io.saasforge.sdk.tenant.TenantContextUnavailableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 将 Starter 内部 Spring Security 状态投影为稳定的 SDK Tenant Context。 */
final class SpringSecurityTenantContextAccessor implements TenantContextAccessor {

    @Override
    public TenantContextSnapshot requireCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof UserAuthenticationContext user
                && user.contextType() == UserAuthenticationContext.ContextType.TENANT) {
            return new TenantContextSnapshot(user.identityId(), user.membershipId(), user.tenantId());
        }
        throw new TenantContextUnavailableException();
    }
}
