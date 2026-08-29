package io.saasforge.testsupport.receiver;

import io.saasforge.starter.security.ServiceAuthenticationContext;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅回显 Starter 建立的非敏感 Service Principal，不能返回原始 Token。 */
@RestController
final class PlatformMechanismReceiverController {
    private final String instanceId;

    PlatformMechanismReceiverController(@Value("${HOSTNAME:local}") String instanceId) {
        this.instanceId = instanceId;
    }

    @PostMapping("/__test/platform-mechanism")
    Response accept() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof ServiceAuthenticationContext context)) {
            throw new IllegalStateException("Starter 未建立 Service Authentication Context");
        }
        return new Response(context.clientId(), context.scopes(), instanceId);
    }

    record Response(UUID clientId, Set<String> scopes, String instanceId) {
    }
}
