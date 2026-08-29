package io.saasforge.starter.security;

import java.security.Principal;
import java.util.UUID;

/** 已由接收端复验的不可变用户身份；不携带 Token、签名或授权声明。 */
public record UserAuthenticationContext(
        UUID identityId,
        ContextType contextType,
        UUID membershipId,
        UUID tenantId) implements Principal {

    public UserAuthenticationContext {
        if (identityId == null || contextType == null) {
            throw new IllegalArgumentException("User Principal 字段不完整");
        }
        boolean tenantContext = membershipId != null && tenantId != null;
        if (tenantContext != (contextType == ContextType.TENANT)
                || membershipId == null != (tenantId == null)) {
            throw new IllegalArgumentException("User Principal 上下文形态非法");
        }
    }

    @Override
    public String getName() {
        return identityId.toString();
    }

    public enum ContextType {
        PLATFORM,
        TENANT
    }
}
