package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.IdentityProvisioningFactRow;
import org.apache.ibatis.annotations.Param;

public interface IdentityProvisioningMapper {

    int lockRequest(@Param("lockKey") String lockKey);

    IdentityProvisioningFactRow find(@Param("row") IdentityProvisioningFactRow row);

    int insert(@Param("row") IdentityProvisioningFactRow row);
}
