package io.saasforge.iam.domain.client;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Client Secret 的非敏感生命周期元数据，不包含 Secret 或其摘要。 */
public final class ClientSecret {

    public static final Duration ROTATION_OVERLAP = Duration.ofHours(24);

    private final UUID id;
    private final UUID clientId;
    private final Instant createdAt;
    private final Instant validUntil;
    private final Instant revokedAt;

    private ClientSecret(UUID id, UUID clientId, Instant createdAt, Instant validUntil, Instant revokedAt) {
        if (clientId == null || createdAt == null) {
            throw new IllegalArgumentException("Client Secret 的必要字段不能为空");
        }
        if (validUntil != null && validUntil.isBefore(createdAt)) {
            throw new IllegalArgumentException("Client Secret 到期时间不合法");
        }
        if (revokedAt != null && revokedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Client Secret 吊销时间不合法");
        }
        this.id = id;
        this.clientId = clientId;
        this.createdAt = createdAt;
        this.validUntil = validUntil;
        this.revokedAt = revokedAt;
    }

    public static ClientSecret issued(UUID clientId, Instant issuedAt) {
        return new ClientSecret(null, clientId, issuedAt, null, null);
    }

    public static ClientSecret restore(UUID id, UUID clientId, Instant createdAt, Instant validUntil, Instant revokedAt) {
        if (id == null) {
            throw new IllegalArgumentException("Client Secret ID 不能为空");
        }
        return new ClientSecret(id, clientId, createdAt, validUntil, revokedAt);
    }

    public ClientSecret identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("Client Secret ID 状态不合法");
        }
        return new ClientSecret(generatedId, clientId, createdAt, validUntil, revokedAt);
    }

    public ClientSecret overlapUntil(Instant at) {
        if (at == null || revokedAt != null || validUntil != null) {
            throw new IllegalStateException("Client Secret 不能再次进入重叠窗口");
        }
        return new ClientSecret(id, clientId, createdAt, at.plus(ROTATION_OVERLAP), null);
    }

    public ClientSecret revoke(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("Client Secret 吊销时间不能为空");
        }
        return new ClientSecret(id, clientId, createdAt, validUntil, revokedAt == null ? at : revokedAt);
    }

    public boolean isValidAt(Instant at) {
        return revokedAt == null && (validUntil == null || validUntil.isAfter(at));
    }

    public UUID id() { return id; }
    public UUID clientId() { return clientId; }
    public Instant createdAt() { return createdAt; }
    public Instant validUntil() { return validUntil; }
    public Instant revokedAt() { return revokedAt; }
}
