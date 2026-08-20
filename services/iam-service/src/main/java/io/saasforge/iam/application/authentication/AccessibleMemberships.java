package io.saasforge.iam.application.authentication;

import java.util.List;
import java.util.UUID;

/** Tenant Access 的只读授权事实边界；失败必须以可重试基础设施错误结束登录。 */
public interface AccessibleMemberships {
    List<AccessibleMembership> findByIdentityId(UUID identityId);
}
