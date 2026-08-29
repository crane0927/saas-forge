package io.saasforge.gateway.config;

import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Service Token 只在 IAM 撤销索引 Ready 且 client_id、kid 均未吊销时放行。 */
final class RedisGatewayServiceTokenRevocationChecker implements GatewayServiceTokenRevocationChecker {

    private final GatewayRedisValueReader redis;
    private final GatewayIamRevocationRedisKeys keys;

    RedisGatewayServiceTokenRevocationChecker(StringRedisTemplate redis, String environment) {
        this(redis.opsForValue()::multiGet, environment);
    }

    RedisGatewayServiceTokenRevocationChecker(GatewayRedisValueReader redis, String environment) {
        this.redis = redis;
        this.keys = new GatewayIamRevocationRedisKeys(environment);
    }

    @Override
    public void assertAllowed(UUID clientId, String kid) {
        List<String> revocationKeys = List.of(keys.ready(), keys.oauthClient(clientId), keys.kid(kid));
        try {
            List<String> values = redis.multiGet(revocationKeys);
            if (values == null || values.size() != revocationKeys.size() || !"1".equals(values.get(0))) {
                throw new GatewayTokenRevocationStatusUnavailableException();
            }
            if (values.get(1) != null || values.get(2) != null) {
                throw new GatewayServiceTokenInvalidException();
            }
        } catch (DataAccessException exception) {
            throw new GatewayTokenRevocationStatusUnavailableException(exception);
        }
    }
}
