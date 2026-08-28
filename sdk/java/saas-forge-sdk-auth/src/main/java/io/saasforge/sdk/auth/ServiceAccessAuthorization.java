package io.saasforge.sdk.auth;

import java.util.Set;
import java.util.UUID;

/** Service Access Token 授权结果只暴露服务身份与显式 Scope，不建立用户 Tenant Context。 */
public record ServiceAccessAuthorization(UUID clientId, Set<String> scopes) {
    public ServiceAccessAuthorization {
        if (clientId == null || scopes == null) {
            throw new IllegalArgumentException("Service Access Token 授权结果不完整");
        }
        scopes = Set.copyOf(scopes);
    }
}
