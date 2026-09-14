package io.saasforge.iam.application.authentication;

public final class RefreshAuthorizationRejectedException extends RuntimeException {
    public static final String CODE = AccessContextUnavailableException.CODE;

    public RefreshAuthorizationRejectedException() {
        super("当前访问上下文已不可用");
    }
}
