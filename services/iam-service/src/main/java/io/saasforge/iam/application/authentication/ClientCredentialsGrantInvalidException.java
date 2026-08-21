package io.saasforge.iam.application.authentication;

public final class ClientCredentialsGrantInvalidException extends RuntimeException {
    public ClientCredentialsGrantInvalidException() {
        super("只支持 client_credentials grant_type");
    }
}
