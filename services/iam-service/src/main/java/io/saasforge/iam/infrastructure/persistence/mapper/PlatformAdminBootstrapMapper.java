package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.PlatformAdminBootstrapStateRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface PlatformAdminBootstrapMapper {
    int lockInitialization();

    PlatformAdminBootstrapStateRow findState();

    int countUntrackedBootstrapState();

    int insert(
            @Param("identityId") UUID identityId,
            @Param("credentialId") UUID credentialId,
            @Param("roleAssignmentId") UUID roleAssignmentId,
            @Param("eventId") UUID eventId,
            @Param("initializedAt") OffsetDateTime initializedAt);
}
