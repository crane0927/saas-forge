package io.saasforge.sdk.auth;

import java.util.UUID;

/** 当前请求中已经验证的用户身份，不携带 Token、签名或原始 Claim。 */
public record IdentityContext(UUID identityId) {

    public IdentityContext {
        if (identityId == null) {
            throw new IllegalArgumentException("Identity Context 缺少 identityId");
        }
    }
}
