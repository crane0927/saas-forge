package io.saasforge.starter.security;

import io.saasforge.sdk.auth.IdentityContext;
import io.saasforge.sdk.auth.IdentityContextAccessor;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 将 Starter 内部 Spring Security 状态投影为稳定的 SDK Identity Context。 */
final class SpringSecurityIdentityContextAccessor implements IdentityContextAccessor {

    @Override
    public Optional<IdentityContext> current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserAuthenticationContext user)) {
            return Optional.empty();
        }
        return Optional.of(new IdentityContext(user.identityId()));
    }
}
