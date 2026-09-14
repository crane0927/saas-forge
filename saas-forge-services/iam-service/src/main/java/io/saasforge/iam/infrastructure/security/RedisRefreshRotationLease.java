package io.saasforge.iam.infrastructure.security;

import io.saasforge.iam.application.authentication.RefreshRotationLease;
import io.saasforge.iam.application.authentication.RefreshRotationUnavailableException;
import io.saasforge.iam.domain.shared.Sha256Digest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisRefreshRotationLease implements RefreshRotationLease {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
                return 1
            end
            if current == ARGV[1] then return 1 end
            return -redis.call('PTTL', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final Duration leaseDuration;

    public RedisRefreshRotationLease(
            StringRedisTemplate redis, String environment, Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Refresh Rotation Lease 必须为正值");
        }
        this.redis = redis;
        this.prefix = "sf:" + environment + ":iam-service:refresh-rotation-lease:v1:";
        this.leaseDuration = leaseDuration;
    }

    @Override
    public Acquisition acquire(Sha256Digest refreshTokenDigest, Sha256Digest idempotencyKeyDigest) {
        try {
            Long result = redis.execute(
                    ACQUIRE,
                    List.of(prefix + HexFormat.of().formatHex(refreshTokenDigest.value())),
                    HexFormat.of().formatHex(idempotencyKeyDigest.value()),
                    Long.toString(leaseDuration.toMillis()));
            if (result == null) {
                throw new RefreshRotationUnavailableException(null);
            }
            if (result > 0) {
                return new Acquisition(true, 0);
            }
            return new Acquisition(false, Math.max(1, (Math.abs(result) + 999) / 1000));
        } catch (DataAccessException exception) {
            throw new RefreshRotationUnavailableException(exception);
        }
    }
}
