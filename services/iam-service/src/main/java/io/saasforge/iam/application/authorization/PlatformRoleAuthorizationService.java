package io.saasforge.iam.application.authorization;

import io.saasforge.iam.domain.authorization.PlatformRoleAssignmentRepository;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

/** 以 IAM 当前权威授予事实判断一个精确 Platform Role。 */
public final class PlatformRoleAuthorizationService {
    private static final Pattern ROLE_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final PlatformRoleAssignmentRepository roles;
    private final Clock clock;

    public PlatformRoleAuthorizationService(PlatformRoleAssignmentRepository roles, Clock clock) {
        this.roles = roles;
        this.clock = clock;
    }

    public boolean isAllowed(UUID identityId, String roleKey) {
        if (identityId == null || identityId.version() != 7
                || roleKey == null || !ROLE_KEY.matcher(roleKey).matches()) {
            throw new IllegalArgumentException("Platform Role 校验请求不合法");
        }
        return roles.hasActiveAssignment(identityId, roleKey, clock.instant());
    }
}
