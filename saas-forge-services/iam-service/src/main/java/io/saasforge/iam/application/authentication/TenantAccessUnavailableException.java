package io.saasforge.iam.application.authentication;

public final class TenantAccessUnavailableException extends RuntimeException {
    public static final String CODE = "TENANT_ACCESS_UNAVAILABLE";

    public TenantAccessUnavailableException(Throwable cause) {
        super("Tenant Access 暂时不可用", cause);
    }
}
