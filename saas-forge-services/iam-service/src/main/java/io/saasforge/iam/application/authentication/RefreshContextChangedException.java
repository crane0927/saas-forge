package io.saasforge.iam.application.authentication;

public final class RefreshContextChangedException extends RuntimeException {
    public static final String CODE = "REFRESH_CONTEXT_CHANGED";

    public RefreshContextChangedException() {
        super("Refresh Token Family 上下文已变化，请基于最新上下文重试");
    }
}
