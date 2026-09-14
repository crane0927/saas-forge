package io.saas.forge.iam.application.client;

import io.saas.forge.iam.domain.client.OAuthClient;
import io.saas.forge.iam.domain.client.OAuthClientStatus;
import io.saas.forge.iam.domain.client.OAuthClientType;
import java.util.List;

/** 平台授权后读取非敏感 Client；游标绑定筛选，失配或过期时拒绝。 */
public interface OAuthClientQueries {
    OperationPage operations(java.util.UUID actorIdentityId, String cursor, int limit);
    CredentialStatus credentialStatus(java.util.UUID clientId);

    record Operation(java.util.UUID operationId, java.util.UUID clientId, String displayName,
            String action, java.time.Instant completedAt, java.time.Instant recoveryUntil, boolean canRecover) { }
    record OperationPage(List<Operation> items, String nextCursor, boolean hasMore) { }
    record CredentialStatus(java.util.UUID clientId, java.time.Instant overlapEndsAt,
            boolean canRotate, boolean canRevoke) { }
    Page list(String name, OAuthClientType type, OAuthClientStatus status, String cursor, int limit);

    record Page(List<OAuthClient> items, String nextCursor, boolean hasMore) { }
}
