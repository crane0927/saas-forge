package io.saasforge.iam.application.bootstrap;

import java.util.UUID;

public record ReservedServiceClientBootstrapInput(
        ReservedServiceClient service,
        UUID clientId,
        String clientSecret) {

    public ReservedServiceClientBootstrapInput {
        if (service == null || clientId == null || clientSecret == null) {
            throw new IllegalArgumentException("保留 OAuth Client 引导输入不能为空");
        }
        if (clientId.version() != 7) {
            throw new IllegalArgumentException("保留 OAuth Client ID 必须是规范 UUIDv7");
        }
    }
}
