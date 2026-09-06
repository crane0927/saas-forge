package io.saasforge.sdk.auth;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 当前请求中已经验证的服务身份，只暴露 client_id 与已授予 Scope。 */
public record ServiceContext(UUID clientId, Set<String> scopes) {

    public ServiceContext {
        if (clientId == null || scopes == null || scopes.isEmpty()
                || scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("Service Context 字段不完整");
        }
        scopes = Collections.unmodifiableSet(new TreeSet<>(scopes));
    }
}
