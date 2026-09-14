package io.saasforge.entitlement.application.authorization;

import java.util.UUID;

/** 平台管理入口对 Platform 用户令牌和 IAM 当前角色进行双重校验。 */
@FunctionalInterface
public interface PlatformAdminAuthorizer {
    UUID authorize(String authorization);
}
