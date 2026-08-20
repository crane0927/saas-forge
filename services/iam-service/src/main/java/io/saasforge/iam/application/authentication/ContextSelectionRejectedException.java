package io.saasforge.iam.application.authentication;

public final class ContextSelectionRejectedException extends RuntimeException {
    public static final String CODE = "CONTEXT_SELECTION_REJECTED";

    public ContextSelectionRejectedException() {
        super("所选 Membership 当前不可访问");
    }
}
