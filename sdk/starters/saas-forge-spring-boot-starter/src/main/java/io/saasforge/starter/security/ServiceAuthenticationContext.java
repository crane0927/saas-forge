package io.saasforge.starter.security;

import java.security.Principal;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** 已由接收端复验的不可变服务身份；Service Principal 不能承载用户 Tenant Context。 */
public record ServiceAuthenticationContext(UUID clientId, Set<String> scopes) implements Principal {

    public ServiceAuthenticationContext {
        if (clientId == null || scopes == null || scopes.isEmpty()
                || scopes.stream().anyMatch(scope -> scope == null || scope.isBlank())) {
            throw new IllegalArgumentException("Service Principal 字段不完整");
        }
        scopes = Collections.unmodifiableSet(new TreeSet<>(scopes));
    }

    @Override
    public String getName() {
        return clientId.toString();
    }
}
