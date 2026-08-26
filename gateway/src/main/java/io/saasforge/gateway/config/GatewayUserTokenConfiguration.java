package io.saasforge.gateway.config;

import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class GatewayUserTokenConfiguration {

    private static final String IAM_SERVICE_ID = "iam-service";

    @Bean
    @ConditionalOnMissingBean(GatewayUserTokenVerifier.class)
    GatewayUserTokenVerifier gatewayUserTokenVerifier(
            LoadBalancerClient loadBalancer,
            StringRedisTemplate redis,
            @Value("${security.jwt.issuer}") String issuer,
            @Value("${saasforge.environment:dev}") String environment) {
        ServiceJwtVerificationKeyResolver keys = kid -> findByKid(loadBalancer, kid);
        return new GatewayUserAccessTokenVerifier(
                new UserAccessTokenSignatureVerifier(
                        keys, Clock.systemUTC(), issuer, "saasforge-api", Duration.ofSeconds(30)),
                new RedisGatewayUserTokenRevocationChecker(redis, environment));
    }

    private Optional<ServiceJwtVerificationKey> findByKid(LoadBalancerClient loadBalancer, String kid) {
        ServiceInstance instance = loadBalancer.choose(IAM_SERVICE_ID);
        if (instance == null) {
            return Optional.empty();
        }
        JwksResponse response = RestClient.create(instance.getUri())
                .get()
                .uri("/.well-known/jwks.json")
                .retrieve()
                .body(JwksResponse.class);
        if (response == null || response.keys() == null) {
            return Optional.empty();
        }
        return response.keys().stream()
                .filter(key -> "RSA".equals(key.kty()) && "RS256".equals(key.alg()) && kid.equals(key.kid()))
                .findFirst()
                .map(key -> new ServiceJwtVerificationKey(key.kid(), key.n(), key.e()));
    }

    private record JwksResponse(List<JwkResponse> keys) {
    }

    private record JwkResponse(String kty, String alg, String use, String kid, String n, String e) {
    }
}
