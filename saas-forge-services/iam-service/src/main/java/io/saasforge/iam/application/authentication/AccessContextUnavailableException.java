package io.saasforge.iam.application.authentication;

public final class AccessContextUnavailableException extends RuntimeException {
    public static final String CODE = "ACCESS_CONTEXT_UNAVAILABLE";

    public AccessContextUnavailableException() {
        super("请求的访问上下文不可用");
    }
}
