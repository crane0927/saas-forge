package io.saasforge.iam.domain.bootstrap;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignment;
import io.saasforge.iam.domain.identity.Identity;
import io.saasforge.iam.domain.identity.PasswordCredential;

/** 用于严格校验幂等重放的已提交 Platform Admin 引导状态。 */
public record PlatformAdminBootstrapState(
        PlatformAdminBootstrapFact fact,
        Identity identity,
        PasswordCredential credential,
        PlatformRoleAssignment roleAssignment,
        int identityCredentialCount,
        int identityRoleAssignmentCount) {

    public PlatformAdminBootstrapState {
        if (fact == null || identity == null || credential == null || roleAssignment == null
                || identityCredentialCount < 0 || identityRoleAssignmentCount < 0) {
            throw new IllegalArgumentException("Platform Admin 引导状态必要字段不合法");
        }
    }
}
