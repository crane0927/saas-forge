package io.saasforge.sdk.tenant;

/** 当前执行边界不存在已经验证的 Tenant Context。 */
public final class TenantContextUnavailableException extends RuntimeException {

    public TenantContextUnavailableException() {
        super("Tenant Context is unavailable.");
    }
}
