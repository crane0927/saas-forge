package io.saasforge.iam.application.authentication;

public final class TenantContextSwitchAccessRejectedException extends RuntimeException {
    public static final String CODE = "ACCESS_CONTEXT_UNAVAILABLE";

    private final boolean clearRefreshCookie;

    private TenantContextSwitchAccessRejectedException(boolean clearRefreshCookie) {
        super("Access context unavailable");
        this.clearRefreshCookie = clearRefreshCookie;
    }

    public static TenantContextSwitchAccessRejectedException currentMembership() {
        return new TenantContextSwitchAccessRejectedException(true);
    }

    public static TenantContextSwitchAccessRejectedException targetMembership() {
        return new TenantContextSwitchAccessRejectedException(false);
    }

    public boolean clearRefreshCookie() {
        return clearRefreshCookie;
    }
}
