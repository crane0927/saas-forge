package io.saasforge.sdk.auth;

/** Platform 用户令牌、撤销状态、IAM 可用性或实时角色任一校验未通过。 */
public final class PlatformAuthorizationDeniedException extends RuntimeException {
    public PlatformAuthorizationDeniedException() {
        super("Platform 管理调用未获授权");
    }

    PlatformAuthorizationDeniedException(Throwable cause) {
        super("Platform 管理调用未获授权", cause);
    }
}
