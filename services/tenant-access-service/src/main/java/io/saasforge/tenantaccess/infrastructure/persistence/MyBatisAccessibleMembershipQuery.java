package io.saasforge.tenantaccess.infrastructure.persistence;

import io.saasforge.tenantaccess.application.membership.AccessibleMembership;
import io.saasforge.tenantaccess.application.membership.AccessibleMembershipQuery;
import io.saasforge.tenantaccess.infrastructure.persistence.mapper.AccessibleMembershipMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisAccessibleMembershipQuery implements AccessibleMembershipQuery {

    private final AccessibleMembershipMapper mapper;

    public MyBatisAccessibleMembershipQuery(AccessibleMembershipMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccessibleMembership> findByIdentityId(UUID identityId) {
        return mapper.findAccessibleByIdentityId(identityId).stream()
                .map(row -> new AccessibleMembership(
                        row.getMembershipId(), row.getTenantId(), row.getTenantDisplayName()))
                .toList();
    }
}
