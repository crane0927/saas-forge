package io.saasforge.sdk.auth;

import java.util.UUID;

/** 调用 IAM 权威状态实时检查一个精确 Platform Role。 */
@FunctionalInterface
public interface PlatformRoleChecker {
    boolean isAllowed(UUID identityId, String roleKey);
}
