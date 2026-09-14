package io.saasforge.iam.application.authentication;

public final class ClientCredentialsScopeRejectedException extends RuntimeException {
    public ClientCredentialsScopeRejectedException() {
        super("请求 Scope 未授予该 OAuth Client");
    }
}
