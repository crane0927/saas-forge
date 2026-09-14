package io.saasforge.iam.infrastructure.persistence.mapper;

import io.saasforge.iam.infrastructure.persistence.record.OAuthClientRow;
import io.saasforge.iam.infrastructure.persistence.record.OAuthClientSecretRow;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

public interface OAuthClientMapper {
    record OperationQuery(UUID actorIdentityId, UUID beforeId, int limit, OffsetDateTime at) { }
    List<io.saasforge.iam.infrastructure.persistence.record.OAuthClientOperationProjection> listOperations(OperationQuery query);
    io.saasforge.iam.infrastructure.persistence.record.OAuthClientCredentialProjection credentialStatus(
            @Param("clientId") UUID clientId, @Param("at") OffsetDateTime at);

    List<OAuthClientRow> listClients(ClientQuery query);

    record ClientQuery(String name, String type, String status, UUID afterId, int limit) { }


    OAuthClientRow insertClient(@Param("row") OAuthClientRow row);

    OAuthClientRow insertClientWithId(@Param("row") OAuthClientRow row);

    OAuthClientSecretRow insertSecret(@Param("row") OAuthClientSecretRow row);

    OAuthClientRow findActiveClientBySecretDigest(@Param("secretDigest") byte[] secretDigest, @Param("at") OffsetDateTime at);

    OAuthClientRow lockClientById(@Param("clientId") UUID clientId);

    OAuthClientRow findClientById(@Param("clientId") UUID clientId);

    OAuthClientRow findActiveByReservedServiceKey(@Param("serviceKey") String serviceKey);

    OAuthClientRow findAnyByReservedServiceKey(@Param("serviceKey") String serviceKey);

    int lockReservedClientBootstrap();

    java.util.List<OAuthClientSecretRow> findSecretsByClientId(@Param("clientId") UUID clientId);

    int hasOverlappingSecret(@Param("clientId") UUID clientId, @Param("at") OffsetDateTime at);

    int expirePrimarySecret(@Param("clientId") UUID clientId, @Param("validUntil") OffsetDateTime validUntil);

    int touchClient(@Param("clientId") UUID clientId, @Param("updatedAt") OffsetDateTime updatedAt);

    int revokeSecret(
            @Param("clientId") UUID clientId,
            @Param("secretId") UUID secretId,
            @Param("revokedAt") OffsetDateTime revokedAt);

    int revokeClient(@Param("clientId") UUID clientId, @Param("revokedAt") OffsetDateTime revokedAt);

    int revokeSecrets(@Param("clientId") UUID clientId, @Param("revokedAt") OffsetDateTime revokedAt);

    List<UUID> findRevokedClientIds();
}
