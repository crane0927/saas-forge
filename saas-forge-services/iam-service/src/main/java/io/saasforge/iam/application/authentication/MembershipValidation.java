package io.saasforge.iam.application.authentication;

import java.util.Optional;
import java.util.UUID;

/** 同步取得 Tenant Access 对指定 Membership 的当前权威判定；允许结果不得缓存。 */
public interface MembershipValidation {

    Optional<ValidatedMembership> validate(UUID identityId, UUID membershipId);
}
