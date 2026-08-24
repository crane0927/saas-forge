package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.membership.MembershipValidationQuery;
import io.saasforge.tenantaccess.application.membership.ValidatedMembership;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.AccessibleMembershipMapper;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public final class MyBatisMembershipValidationQuery implements MembershipValidationQuery {
    private final AccessibleMembershipMapper mapper;

    public MyBatisMembershipValidationQuery(AccessibleMembershipMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ValidatedMembership> findUsable(UUID identityId, UUID membershipId) {
        return Optional.ofNullable(mapper.findUsableTenantId(identityId, membershipId))
                .map(tenantId -> new ValidatedMembership(membershipId, tenantId));
    }
}
