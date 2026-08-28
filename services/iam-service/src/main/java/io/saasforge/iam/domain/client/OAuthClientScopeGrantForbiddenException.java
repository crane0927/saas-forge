package io.saasforge.iam.domain.client;

public final class OAuthClientScopeGrantForbiddenException extends RuntimeException {
    public static final String CODE = "OAUTH_CLIENT_SCOPE_GRANT_FORBIDDEN";

    public OAuthClientScopeGrantForbiddenException() {
        super("Runtime OAuth Client 只能获得公开 Runtime Scope");
    }
}
