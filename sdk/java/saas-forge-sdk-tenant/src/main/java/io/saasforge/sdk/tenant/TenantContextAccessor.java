package io.saasforge.sdk.tenant;

/** 只读访问当前执行边界内已经验证的 Tenant Context；上下文缺失时必须失败。 */
@FunctionalInterface
public interface TenantContextAccessor {

    /**
     * 返回当前完整 Tenant Context。
     *
     * @throws TenantContextUnavailableException 当前执行边界没有 Tenant Context
     */
    TenantContextSnapshot requireCurrent();
}
