package io.saasforge.starter.security;

import io.saasforge.sdk.auth.ServiceContext;
import io.saasforge.sdk.auth.ServiceContextAccessor;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 将 Starter 内部 Spring Security 状态投影为稳定的 SDK Service Context。 */
final class SpringSecurityServiceContextAccessor implements ServiceContextAccessor {

    @Override
    public Optional<ServiceContext> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ServiceAuthenticationContext service)) {
            return Optional.empty();
        }
        return Optional.of(new ServiceContext(service.clientId(), service.scopes()));
    }
}
