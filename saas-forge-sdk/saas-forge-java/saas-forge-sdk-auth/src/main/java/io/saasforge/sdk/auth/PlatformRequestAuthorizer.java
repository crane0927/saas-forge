package io.saasforge.sdk.auth;

import java.util.UUID;
import java.util.regex.Pattern;

/** 平台管理入口共用的用户令牌与 IAM 实时角色双校验边界。 */
public final class PlatformRequestAuthorizer {
    private static final Pattern ROLE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final UserAccessTokenVerifier userTokens;
    private final PlatformRoleChecker roles;

    public PlatformRequestAuthorizer(UserAccessTokenVerifier userTokens, PlatformRoleChecker roles) {
        if (userTokens == null || roles == null) {
            throw new IllegalArgumentException("Platform 请求鉴权依赖不能为空");
        }
        this.userTokens = userTokens;
        this.roles = roles;
    }

    /** 只有 Platform Token 与 IAM 当前精确角色同时成立时才返回 Identity ID。 */
    public UUID authorize(String authorization, String roleKey) {
        if (roleKey == null || !ROLE_KEY.matcher(roleKey).matches()) {
            throw new PlatformAuthorizationDeniedException();
        }
        try {
            UUID identityId = userTokens.verifyPlatformToken(authorization).identityId();
            if (!roles.isAllowed(identityId, roleKey)) {
                throw new PlatformAuthorizationDeniedException();
            }
            return identityId;
        } catch (PlatformAuthorizationDeniedException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // IAM、JWKS 或撤销索引不可用时不得退化为离线角色推导。
            throw new PlatformAuthorizationDeniedException(exception);
        }
    }
}
