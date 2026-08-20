package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.PlatformRoleAssignmentRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface PlatformRoleAssignmentMapper {

    PlatformRoleAssignmentRow insert(@Param("row") PlatformRoleAssignmentRow row);

    int countActive(@Param("identityId") UUID identityId, @Param("at") OffsetDateTime at);
}
