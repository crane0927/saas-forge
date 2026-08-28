package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.OAuthClientRow;
import io.saasforge.iam.infrastructure.persistence.record.OAuthClientSecretRow;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface OAuthClientMapper {

    OAuthClientRow insertClient(@Param("row") OAuthClientRow row);

    OAuthClientRow insertClientWithId(@Param("row") OAuthClientRow row);

    OAuthClientSecretRow insertSecret(@Param("row") OAuthClientSecretRow row);

    OAuthClientRow findActiveClientBySecretDigest(@Param("secretDigest") byte[] secretDigest, @Param("at") OffsetDateTime at);

    OAuthClientRow lockClientById(@Param("clientId") UUID clientId);

    OAuthClientRow findClientById(@Param("clientId") UUID clientId);

    int lockReservedClientBootstrap();

    java.util.List<OAuthClientSecretRow> findSecretsByClientId(@Param("clientId") UUID clientId);

    int hasOverlappingSecret(@Param("clientId") UUID clientId, @Param("at") OffsetDateTime at);

    int expirePrimarySecret(@Param("clientId") UUID clientId, @Param("validUntil") OffsetDateTime validUntil);

    int touchClient(@Param("clientId") UUID clientId, @Param("updatedAt") OffsetDateTime updatedAt);

    int revokeClient(@Param("clientId") UUID clientId, @Param("revokedAt") OffsetDateTime revokedAt);

    int revokeSecrets(@Param("clientId") UUID clientId, @Param("revokedAt") OffsetDateTime revokedAt);
}
