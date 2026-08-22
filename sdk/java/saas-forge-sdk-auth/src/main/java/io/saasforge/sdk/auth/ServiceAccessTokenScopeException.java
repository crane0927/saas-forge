package io.saasforge.sdk.auth;

/** Service Access Token 有效，但不具备当前内部操作要求的精确 Scope。 */
public final class ServiceAccessTokenScopeException extends ServiceAccessTokenInvalidException {
    public ServiceAccessTokenScopeException() {
        super("Service Access Token Scope 不足");
    }
}
