package io.saasforge.entitlement.infrastructure.security;

import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 复用 IAM 的 Service Access Token 撤销投影；Ready 状态缺失时失败关闭。 */
public final class RedisServiceAccessTokenRevocationChecker
        implements ServiceAccessTokenRevocationChecker {
    private final StringRedisTemplate redis;
    private final String prefix;

    public RedisServiceAccessTokenRevocationChecker(StringRedisTemplate redis, String environment) {
        this.redis = redis;
        this.prefix = "sf:" + environment + ":iam-service:";
    }

    @Override
    public boolean isRevoked(UUID clientId, String kid) {
        List<String> values = redis.opsForValue().multiGet(List.of(
                prefix + "revocation-index-ready:v1:state",
                prefix + "oauth-client-revocation:v1:" + clientId,
                prefix + "signing-kid-revocation:v1:" + digest(kid)));
        if (values == null || values.size() != 3 || !"1".equals(values.get(0))) {
            throw new IllegalStateException("IAM 撤销索引不可用");
        }
        return values.get(1) != null || values.get(2) != null;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
