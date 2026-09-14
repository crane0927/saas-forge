package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.application.client.OAuthClientQueries;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.iam.domain.client.OAuthClientType;
import io.saasforge.iam.infrastructure.persistence.mapper.OAuthClientMapper;
import java.time.Clock;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisOAuthClientQueries implements OAuthClientQueries {
    private final OAuthClientMapper mapper;
    private final OAuthClientCursor cursors;
    private final Clock clock;

    public MyBatisOAuthClientQueries(OAuthClientMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.cursors = new OAuthClientCursor(clock);
        this.clock = clock;
    }

    @Override
    public OperationPage operations(java.util.UUID actorIdentityId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid page size");
        String scope = "oauth-client-operations:" + actorIdentityId;
        var rows = mapper.listOperations(new OAuthClientMapper.OperationQuery(actorIdentityId,
                cursors.decode(scope, cursor), limit + 1, IamTime.asOffsetDateTime(clock.instant())));
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(row -> new Operation(row.operationId(), row.clientId(),
                row.displayName(), row.action(), IamTime.asInstant(row.completedAt()),
                row.recoveryUntil() == null ? null : IamTime.asInstant(row.recoveryUntil()), row.canRecover())).toList();
        return new OperationPage(items, more ? cursors.encode(scope, items.get(items.size() - 1).operationId()) : null, more);
    }

    @Override
    public CredentialStatus credentialStatus(java.util.UUID clientId) {
        var row = mapper.credentialStatus(clientId, IamTime.asOffsetDateTime(clock.instant()));
        if (row == null) throw io.saasforge.iam.application.client.OAuthClientManagementException.notFound();
        return new CredentialStatus(row.clientId(), row.overlapEndsAt() == null ? null
                : IamTime.asInstant(row.overlapEndsAt()), row.canRotate(), row.canRevoke());
    }

    @Override
    public Page list(String name, OAuthClientType type, OAuthClientStatus status, String cursor, int limit) {
        if (limit < 1 || limit > 100 || (name != null && name.length() > 200)) {
            throw new IllegalArgumentException("Invalid filter or page size");
        }
        String filter = name == null ? "" : name;
        String scope = "oauth-clients:" + filter + ":" + type + ":" + status;
        var rows = mapper.listClients(new OAuthClientMapper.ClientQuery(filter,
                type == null ? null : type.name(), status == null ? null : status.name(),
                cursors.decode(scope, cursor), limit + 1));
        boolean more = rows.size() > limit;
        var items = rows.stream().limit(limit).map(MyBatisOAuthClientRepository::toDomain).toList();
        return new Page(items, more ? cursors.encode(scope, items.get(items.size() - 1).id()) : null, more);
    }
}
