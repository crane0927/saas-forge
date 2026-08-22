package io.saasforge.sdk.auth;

public final class UserAccessTokenInvalidException extends RuntimeException {
    public UserAccessTokenInvalidException() {
        super("Platform User Access Token 无效");
    }

    UserAccessTokenInvalidException(Throwable cause) {
        super("Platform User Access Token 无效", cause);
    }
}
