package io.saas.forge.iam.application.authentication;

public final class ClientCredentialsInvalidException extends RuntimeException {
    public ClientCredentialsInvalidException() {
        super("OAuth Client 认证失败");
    }
}
