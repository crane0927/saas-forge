package io.saasforge.sdk.auth;

public final class ServiceAccessTokenInvalidException extends RuntimeException {
    public ServiceAccessTokenInvalidException() {
        super("Service Access Token 无效");
    }

    ServiceAccessTokenInvalidException(Throwable cause) {
        super("Service Access Token 无效", cause);
    }
}
