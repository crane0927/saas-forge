package io.saasforge.iam.application.authentication;

public final class ContextSelectionSessionInvalidException extends RuntimeException {
    public static final String CODE = "CONTEXT_SELECTION_SESSION_INVALID";

    public ContextSelectionSessionInvalidException() {
        super("Tenant 上下文选择会话无效或已失效");
    }
}
