package io.saasforge.sdk.auth;

public class ServiceAccessTokenInvalidException extends RuntimeException {
    public ServiceAccessTokenInvalidException() {
        super("Service Access Token 无效");
    }

    ServiceAccessTokenInvalidException(Throwable cause) {
        super("Service Access Token 无效", cause);
    }

    protected ServiceAccessTokenInvalidException(String message) {
        super(message);
    }
}
