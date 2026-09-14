package io.saasforge.tenantaccess.application.membership;

import java.util.List;
import java.util.UUID;

/**
 * 查询 Identity 当前可进入的 Tenant 上下文。
 *
 * <p>结果按 Tenant Display Name、Membership ID 排序并最多包含 101 条；第 101 条只用于让调用方识别超限。
 */
public interface AccessibleMembershipQuery {

    List<AccessibleMembership> findByIdentityId(UUID identityId);
}
