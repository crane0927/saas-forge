package io.saasforge.iam.infrastructure.security;

import io.saasforge.iam.application.authentication.RevocationIndex;
import io.saasforge.iam.application.authentication.RevocationIndexUnavailableException;
import io.saasforge.iam.domain.session.DurableRevocation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisRevocationIndex implements RevocationIndex {
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    private static final DefaultRedisScript<Long> UPSERT = new DefaultRedisScript<>("""
            local current = redis.call('PTTL', KEYS[1])
            local requested = tonumber(ARGV[1])
            if current < requested then redis.call('SET', KEYS[1], '1', 'PX', requested) end
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> CHECK = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 then return 1 end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> CHECK_TOKEN = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= '1' then return -1 end
            if redis.call('EXISTS', KEYS[2]) == 1 or redis.call('EXISTS', KEYS[3]) == 1 then return 1 end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final String readyKey;
    private volatile boolean recoveryRequired;

    public RedisRevocationIndex(StringRedisTemplate redis, String environment) {
        this.redis = redis;
        this.prefix = "sf:" + environment + ":iam-service:";
        this.readyKey = prefix + "revocation-index-ready:v1:state";
    }

    @Override
    public void revokeJti(UUID jti, Instant expiresAt, Instant at) {
        try {
            write(jtiKey(jti), expiresAt.plus(CLOCK_SKEW), at);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void markNotReady() {
        recoveryRequired = true;
        try {
            redis.opsForValue().set(readyKey, "0");
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public void rebuild(List<DurableRevocation> revocations, Instant at) {
        try {
            for (DurableRevocation revocation : revocations) {
                if (revocation.jtiRevoked()) {
                    write(jtiKey(revocation.jti()), revocation.expiresAt().plus(CLOCK_SKEW), at);
                }
            }
            Map<String, Instant> kidExpiries = revocations.stream()
                    .filter(DurableRevocation::kidRevoked)
                    .collect(Collectors.toMap(
                            DurableRevocation::kid,
                            DurableRevocation::expiresAt,
                            (left, right) -> Comparator.<Instant>naturalOrder().compare(left, right) >= 0 ? left : right));
            kidExpiries.forEach((kid, expiresAt) -> write(kidKey(kid), expiresAt.plus(CLOCK_SKEW), at));
            redis.opsForValue().set(readyKey, "1");
            recoveryRequired = false;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean isJtiRevoked(UUID jti) {
        return isRevoked(jtiKey(jti));
    }

    @Override
    public boolean isKidRevoked(String kid) {
        return isRevoked(kidKey(kid));
    }

    @Override
    public boolean isTokenRevoked(UUID jti, String kid) {
        try {
            Long result = redis.execute(CHECK_TOKEN, List.of(readyKey, jtiKey(jti), kidKey(kid)));
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private boolean isRevoked(String revocationKey) {
        try {
            Long result = redis.execute(CHECK, List.of(readyKey, revocationKey));
            if (result == null || result < 0) {
                throw new RevocationIndexUnavailableException();
            }
            return result == 1;
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public boolean isReady() {
        try {
            return !recoveryRequired && "1".equals(redis.opsForValue().get(readyKey));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void write(String key, Instant rejectUntil, Instant at) {
        long ttlMillis = Math.max(1, Duration.between(at, rejectUntil).toMillis());
        redis.execute(UPSERT, List.of(key), Long.toString(ttlMillis));
    }

    private String jtiKey(UUID jti) {
        return prefix + "jwt-jti-revocation:v1:" + digest(jti.toString());
    }

    private String kidKey(String kid) {
        return prefix + "signing-kid-revocation:v1:" + digest(kid);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }

    private RevocationIndexUnavailableException unavailable(DataAccessException exception) {
        recoveryRequired = true;
        return new RevocationIndexUnavailableException(exception);
    }
}
