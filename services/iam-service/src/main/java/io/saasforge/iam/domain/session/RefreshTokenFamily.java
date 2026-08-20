package io.saasforge.iam.domain.session;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** 浏览器 Refresh Token 的稳定会话边界，不保存 Token 明文或摘要。 */
public final class RefreshTokenFamily {

    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(8);
    private static final Duration IDLE_LIFETIME = Duration.ofMinutes(30);

    private final UUID id;
    private final UUID identityId;
    private final UUID membershipId;
    private final UUID tenantId;
    private final Instant lastUsedAt;
    private final Instant absoluteExpiresAt;
    private final Instant revokedAt;

    private RefreshTokenFamily(
            UUID id,
            UUID identityId,
            UUID membershipId,
            UUID tenantId,
            Instant lastUsedAt,
            Instant absoluteExpiresAt,
            Instant revokedAt) {
        if (identityId == null || lastUsedAt == null || absoluteExpiresAt == null) {
            throw new IllegalArgumentException("Refresh Token Family 的必要字段不能为空");
        }
        if ((membershipId == null) != (tenantId == null)) {
            throw new IllegalArgumentException("Membership 与 Tenant 上下文必须同时存在或同时为空");
        }
        if (!absoluteExpiresAt.isAfter(lastUsedAt)) {
            throw new IllegalArgumentException("绝对到期时间必须晚于最后使用时间");
        }
        if (revokedAt != null && revokedAt.isBefore(lastUsedAt)) {
            throw new IllegalArgumentException("撤销时间不能早于最后使用时间");
        }
        this.id = id;
        this.identityId = identityId;
        this.membershipId = membershipId;
        this.tenantId = tenantId;
        this.lastUsedAt = lastUsedAt;
        this.absoluteExpiresAt = absoluteExpiresAt;
        this.revokedAt = revokedAt;
    }

    public static RefreshTokenFamily start(UUID identityId, UUID membershipId, UUID tenantId, Instant loginAt) {
        return new RefreshTokenFamily(null, identityId, membershipId, tenantId, loginAt, loginAt.plus(ABSOLUTE_LIFETIME), null);
    }

    public static RefreshTokenFamily restore(
            UUID id,
            UUID identityId,
            UUID membershipId,
            UUID tenantId,
            Instant lastUsedAt,
            Instant absoluteExpiresAt,
            Instant revokedAt) {
        if (id == null) {
            throw new IllegalArgumentException("Refresh Token Family ID 不能为空");
        }
        return new RefreshTokenFamily(id, identityId, membershipId, tenantId, lastUsedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily identifiedBy(UUID generatedId) {
        if (generatedId == null || id != null) {
            throw new IllegalStateException("Refresh Token Family ID 状态不合法");
        }
        return new RefreshTokenFamily(generatedId, identityId, membershipId, tenantId, lastUsedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily recordUse(UUID nextMembershipId, UUID nextTenantId, Instant usedAt) {
        requireUsableAt(usedAt);
        return new RefreshTokenFamily(id, identityId, nextMembershipId, nextTenantId, usedAt, absoluteExpiresAt, revokedAt);
    }

    public RefreshTokenFamily revoke(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("撤销时间不能为空");
        }
        if (revokedAt != null) {
            return this;
        }
        return new RefreshTokenFamily(id, identityId, membershipId, tenantId, lastUsedAt, absoluteExpiresAt, at);
    }

    public boolean isUsableAt(Instant at) {
        return revokedAt == null && at.isBefore(absoluteExpiresAt) && at.isBefore(lastUsedAt.plus(IDLE_LIFETIME));
    }

    public void requireUsableAt(Instant at) {
        if (at == null || !isUsableAt(at)) {
            throw new IllegalStateException("Refresh Token Family 已失效");
        }
    }

    public UUID id() { return id; }
    public UUID identityId() { return identityId; }
    public UUID membershipId() { return membershipId; }
    public UUID tenantId() { return tenantId; }
    public Instant lastUsedAt() { return lastUsedAt; }
    public Instant absoluteExpiresAt() { return absoluteExpiresAt; }
    public Instant revokedAt() { return revokedAt; }
}
