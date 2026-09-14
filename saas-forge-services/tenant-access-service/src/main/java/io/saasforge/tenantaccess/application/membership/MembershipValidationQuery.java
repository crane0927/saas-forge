package io.saasforge.tenantaccess.application.membership;

import java.util.Optional;
import java.util.UUID;

/** 从 Tenant Access 权威状态判断指定 Membership 当前是否可供 Identity 使用。 */
public interface MembershipValidationQuery {

    Optional<ValidatedMembership> findUsable(UUID identityId, UUID membershipId);
}
