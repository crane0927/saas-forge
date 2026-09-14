package io.saas.forge.tenantaccess.infrastructure.persistence.mapper;

import io.saas.forge.tenantaccess.infrastructure.persistence.record.AccessibleMembershipRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface AccessibleMembershipMapper {

    List<AccessibleMembershipRow> findAccessibleByIdentityId(@Param("identityId") UUID identityId);

    UUID findUsableTenantId(
            @Param("identityId") UUID identityId,
            @Param("membershipId") UUID membershipId);
}
