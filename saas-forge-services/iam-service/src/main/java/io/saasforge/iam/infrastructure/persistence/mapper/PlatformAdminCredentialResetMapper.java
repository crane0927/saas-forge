package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.PlatformAdminCredentialResetFactRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface PlatformAdminCredentialResetMapper {
    int lockReset();

    PlatformAdminCredentialResetFactRow findByRequestId(@Param("resetRequestId") UUID resetRequestId);

    int insert(
            @Param("resetRequestId") UUID resetRequestId,
            @Param("identityId") UUID identityId,
            @Param("credentialId") UUID credentialId,
            @Param("eventId") UUID eventId,
            @Param("resetAt") OffsetDateTime resetAt);
}
