package io.saasforge.testsupport.receiver;

import io.saasforge.sdk.auth.ServiceContext;
import io.saasforge.sdk.auth.ServiceContextAccessor;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 仅通过 SDK 接口回显 Starter 建立的非敏感 Service Context，不能返回原始 Token。 */
@RestController
final class PlatformMechanismReceiverController {
    private final String instanceId;
    private final ServiceContextAccessor serviceContexts;

    PlatformMechanismReceiverController(
            @Value("${HOSTNAME:local}") String instanceId,
            ServiceContextAccessor serviceContexts) {
        this.instanceId = instanceId;
        this.serviceContexts = serviceContexts;
    }

    @PostMapping("/__test/platform-mechanism")
    Response accept() {
        ServiceContext context = serviceContexts.current()
                .orElseThrow(() -> new IllegalStateException("Starter 未建立 Service Context"));
        return new Response(context.clientId(), context.scopes(), instanceId);
    }

    record Response(UUID clientId, Set<String> scopes, String instanceId) {
    }
}
