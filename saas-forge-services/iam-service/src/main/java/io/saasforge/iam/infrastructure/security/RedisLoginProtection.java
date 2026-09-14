package io.saasforge.iam.infrastructure.security;

import io.saasforge.iam.application.authentication.AuthenticationProtectionUnavailableException;
import io.saasforge.iam.application.authentication.LoginProtection;
import io.saasforge.iam.domain.identity.NormalizedEmail;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisLoginProtection implements LoginProtection {
    private static final DefaultRedisScript<Long> RECORD_FAILURE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[2]) == 1 then return 2 end
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if count >= tonumber(ARGV[2]) then
              redis.call('SET', KEYS[2], '1', 'PX', ARGV[3], 'NX')
              redis.call('DEL', KEYS[1])
              return 2
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Duration failureWindow;
    private final int maximumFailures;
    private final Duration lockDuration;

    public RedisLoginProtection(
            StringRedisTemplate redis,
            String environment,
            Duration failureWindow,
            int maximumFailures,
            Duration lockDuration) {
        if (failureWindow == null || failureWindow.isZero() || failureWindow.isNegative()
                || maximumFailures <= 0 || lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("登录保护参数必须为正数");
        }
        this.redis = redis;
        this.keyPrefix = "sf:" + environment + ":iam-service:";
        this.failureWindow = failureWindow;
        this.maximumFailures = maximumFailures;
        this.lockDuration = lockDuration;
    }

    @Override
    public boolean isLocked(NormalizedEmail email) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(lockKey(email)));
        } catch (DataAccessException exception) {
            throw new AuthenticationProtectionUnavailableException(exception);
        }
    }

    @Override
    public void recordCredentialFailure(NormalizedEmail email) {
        try {
            redis.execute(RECORD_FAILURE, List.of(failureKey(email), lockKey(email)),
                    Long.toString(failureWindow.toMillis()), Integer.toString(maximumFailures),
                    Long.toString(lockDuration.toMillis()));
        } catch (DataAccessException exception) {
            throw new AuthenticationProtectionUnavailableException(exception);
        }
    }

    @Override
    public void clearCredentialFailures(NormalizedEmail email) {
        try {
            redis.delete(failureKey(email));
        } catch (DataAccessException exception) {
            throw new AuthenticationProtectionUnavailableException(exception);
        }
    }

    private String failureKey(NormalizedEmail email) {
        return keyPrefix + "login-failure:v1:" + subjectDigest(email);
    }

    private String lockKey(NormalizedEmail email) {
        return keyPrefix + "login-lock:v1:" + subjectDigest(email);
    }

    private static String subjectDigest(NormalizedEmail email) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(email.value().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("缺少 SHA-256 算法支持", exception);
        }
    }
}
