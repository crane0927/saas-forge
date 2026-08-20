package io.saasforge.iam.domain.client;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 服务身份 OAuth Client，不携带用户或 Tenant 上下文。 */
public final class OAuthClient {

    private final UUID id;
    private final String displayName;
    private final Set<OAuthScope> allowedScopes;
    private final OAuthClientStatus status;
    private final Instant createdAt;
    private final Instant revokedAt;

    private OAuthClient(
            UUID id,
            String displayName,
            Set<OAuthScope> allowedScopes,
            OAuthClientStatus status,
            Instant createdAt,
            Instant revokedAt) {
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("OAuth Client 显示名必须为 1 到 200 个字符");
        }
        if (allowedScopes == null || allowedScopes.isEmpty()) {
            throw new IllegalArgumentException("OAuth Client 必须声明至少一个 Scope");
        }
        if (status == null || createdAt == null) {
            throw new IllegalArgumentException("OAuth Client 的必要字段不能为空");
        }
        if ((status == OAuthClientStatus.REVOKED) != (revokedAt != null)) {
            throw new IllegalArgumentException("OAuth Client 吊销状态不一致");
        }
        this.id = id;
        this.displayName = displayName;
        this.allowedScopes = Collections.unmodifiableSet(new LinkedHashSet<>(allowedScopes));
        this.status = status;
        this.createdAt = createdAt;
        this.revokedAt = revokedAt;
    }

    public static OAuthClient register(String displayName, Set<OAuthScope> allowedScopes, Instant createdAt) {
        return new OAuthClient(null, displayName, allowedScopes, OAuthClientStatus.ACTIVE, createdAt, null);
    }

    public static OAuthClient restore(
            UUID id,
            String displayName,
            Set<OAuthScope> allowedScopes,
            OAuthClientStatus status,
            Instant createdAt,
            Instant revokedAt) {
        if (id == null) {
            throw new IllegalArgumentException("OAuth Client ID 不能为空");
        }
        return new OAuthClient(id, displayName, allowedScopes, status, createdAt, revokedAt);
    }

    public OAuthClient identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("OAuth Client ID 状态不合法");
        }
        return new OAuthClient(generatedId, displayName, allowedScopes, status, createdAt, revokedAt);
    }

    public OAuthClient revoke(Instant at) {
        if (status == OAuthClientStatus.REVOKED) {
            return this;
        }
        if (at == null || at.isBefore(createdAt)) {
            throw new IllegalArgumentException("OAuth Client 吊销时间不合法");
        }
        return new OAuthClient(id, displayName, allowedScopes, OAuthClientStatus.REVOKED, createdAt, at);
    }

    public void requireActive() {
        if (status != OAuthClientStatus.ACTIVE) {
            throw new IllegalStateException("OAuth Client 已被吊销");
        }
    }

    public UUID id() { return id; }
    public String displayName() { return displayName; }
    public Set<OAuthScope> allowedScopes() { return allowedScopes; }
    public OAuthClientStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant revokedAt() { return revokedAt; }
}
