package io.saasforge.iam.infrastructure.persistence;

import io.saasforge.iam.domain.client.ClientSecret;
import io.saasforge.iam.domain.client.OAuthClient;
import io.saasforge.iam.domain.client.OAuthClientBootstrapState;
import io.saasforge.iam.domain.client.OAuthClientRepository;
import io.saasforge.iam.domain.client.OAuthClientStatus;
import io.saasforge.iam.domain.client.OAuthClientType;
import io.saasforge.iam.domain.client.OAuthScope;
import io.saasforge.iam.domain.client.ReservedServiceKey;
import io.saasforge.iam.domain.shared.Sha256Digest;
import io.saasforge.iam.infrastructure.persistence.mapper.OAuthClientMapper;
import io.saasforge.iam.infrastructure.persistence.record.OAuthClientRow;
import io.saasforge.iam.infrastructure.persistence.record.OAuthClientSecretRow;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MyBatisOAuthClientRepository implements OAuthClientRepository {

    private final OAuthClientMapper mapper;

    public MyBatisOAuthClientRepository(OAuthClientMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public OAuthClient create(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
        OAuthClient persisted = toDomain(mapper.insertClient(toRow(client)));
        mapper.insertSecret(secretRow(persisted.id(), initialSecretDigest, issuedAt));
        return persisted;
    }

    @Override
    @Transactional
    public OAuthClient createWithId(OAuthClient client, Sha256Digest initialSecretDigest, Instant issuedAt) {
        OAuthClient persisted = toDomain(mapper.insertClientWithId(toRow(client)));
        mapper.insertSecret(secretRow(persisted.id(), initialSecretDigest, issuedAt));
        return persisted;
    }

    @Override
    public void lockReservedClientBootstrap() {
        if (mapper.lockReservedClientBootstrap() != 1) {
            throw new IllegalStateException("保留 OAuth Client 引导锁获取失败");
        }
    }

    @Override
    public Optional<OAuthClientBootstrapState> findBootstrapState(UUID clientId) {
        return Optional.ofNullable(mapper.lockClientById(clientId)).map(row -> new OAuthClientBootstrapState(
                toDomain(row),
                mapper.findSecretsByClientId(clientId).stream()
                        .map(secret -> new OAuthClientBootstrapState.SecretState(
                                Sha256Digest.of(secret.getSecretDigest()),
                                IamTime.asInstant(secret.getValidUntil()),
                                IamTime.asInstant(secret.getRevokedAt())))
                        .toList()));
    }

    @Override
    public Optional<OAuthClient> findById(UUID clientId) {
        return Optional.ofNullable(mapper.findClientById(clientId)).map(MyBatisOAuthClientRepository::toDomain);
    }

    @Override
    public Optional<OAuthClient> findActiveBySecretDigest(Sha256Digest secretDigest, Instant at) {
        return Optional.ofNullable(mapper.findActiveClientBySecretDigest(secretDigest.value(), IamTime.asOffsetDateTime(at)))
                .map(MyBatisOAuthClientRepository::toDomain);
    }

    @Override
    @Transactional
    public ClientSecret rotate(UUID clientId, Sha256Digest nextSecretDigest, Instant at) {
        OAuthClientRow row = mapper.lockClientById(clientId);
        if (row == null) {
            throw new IllegalArgumentException("OAuth Client 不存在");
        }
        OAuthClient client = toDomain(row);
        client.requireActive();
        if (mapper.hasOverlappingSecret(clientId, IamTime.asOffsetDateTime(at)) != 0) {
            throw new IllegalStateException("Client Secret 重叠窗口尚未结束");
        }
        if (mapper.expirePrimarySecret(clientId, IamTime.asOffsetDateTime(at.plus(ClientSecret.ROTATION_OVERLAP))) != 1) {
            throw new IllegalStateException("OAuth Client 缺少可轮换的有效 Secret");
        }
        return toDomain(mapper.insertSecret(secretRow(clientId, nextSecretDigest, at)));
    }

    @Override
    @Transactional
    public void revoke(UUID clientId, Instant at) {
        OAuthClientRow row = mapper.lockClientById(clientId);
        if (row == null) {
            throw new IllegalArgumentException("OAuth Client 不存在");
        }
        OAuthClient client = toDomain(row);
        if (client.status() == OAuthClientStatus.REVOKED) {
            return;
        }
        client.revoke(at);
        if (mapper.revokeClient(clientId, IamTime.asOffsetDateTime(at)) != 1) {
            throw new IllegalStateException("OAuth Client 吊销并发冲突");
        }
        mapper.revokeSecrets(clientId, IamTime.asOffsetDateTime(at));
    }

    private static OAuthClientRow toRow(OAuthClient client) {
        OAuthClientRow row = new OAuthClientRow();
        row.setId(client.id());
        row.setDisplayName(client.displayName());
        row.setClientType(client.clientType().name());
        row.setReservedServiceKey(client.reservedServiceKey() == null ? null : client.reservedServiceKey().name());
        row.setAllowedScopes(client.allowedScopes().stream().map(OAuthScope::value).toArray(String[]::new));
        row.setClientStatus(client.status().name());
        row.setCreatedAt(IamTime.asOffsetDateTime(client.createdAt()));
        row.setUpdatedAt(IamTime.asOffsetDateTime(client.updatedAt()));
        row.setRevokedAt(IamTime.asOffsetDateTime(client.revokedAt()));
        return row;
    }

    private static OAuthClientSecretRow secretRow(UUID clientId, Sha256Digest secretDigest, Instant issuedAt) {
        OAuthClientSecretRow row = new OAuthClientSecretRow();
        row.setClientId(clientId);
        row.setSecretDigest(secretDigest.value());
        row.setCreatedAt(IamTime.asOffsetDateTime(issuedAt));
        return row;
    }

    private static OAuthClient toDomain(OAuthClientRow row) {
        LinkedHashSet<OAuthScope> scopes = Arrays.stream(row.getAllowedScopes())
                .map(OAuthScope::fromValue)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return OAuthClient.restore(row.getId(), row.getDisplayName(), OAuthClientType.valueOf(row.getClientType()),
                row.getReservedServiceKey() == null ? null : ReservedServiceKey.valueOf(row.getReservedServiceKey()),
                scopes, OAuthClientStatus.valueOf(row.getClientStatus()), IamTime.asInstant(row.getCreatedAt()),
                IamTime.asInstant(row.getUpdatedAt()), IamTime.asInstant(row.getRevokedAt()));
    }

    private static ClientSecret toDomain(OAuthClientSecretRow row) {
        return ClientSecret.restore(row.getId(), row.getClientId(), IamTime.asInstant(row.getCreatedAt()),
                IamTime.asInstant(row.getValidUntil()), IamTime.asInstant(row.getRevokedAt()));
    }
}
