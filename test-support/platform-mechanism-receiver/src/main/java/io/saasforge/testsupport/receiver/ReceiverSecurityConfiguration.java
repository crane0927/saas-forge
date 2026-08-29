package io.saasforge.testsupport.receiver;

import io.saasforge.sdk.auth.ServiceAccessTokenRevocationChecker;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import io.saasforge.sdk.auth.UserAccessTokenSignatureVerifier;
import io.saasforge.starter.security.UserAccessTokenContextRevocationChecker;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
class ReceiverSecurityConfiguration {

    @Bean
    UserAccessTokenSignatureVerifier receiverUserAccessTokenSignatureVerifier(
            LoadBalancerClient loadBalancer,
            @Value("${security.jwt.issuer}") String issuer) {
        return new UserAccessTokenSignatureVerifier(
                keyResolver(loadBalancer), Clock.systemUTC(), issuer, "saasforge-api", Duration.ofSeconds(30));
    }

    @Bean
    ServiceAccessTokenSignatureVerifier receiverServiceAccessTokenSignatureVerifier(
            LoadBalancerClient loadBalancer,
            @Value("${security.jwt.issuer}") String issuer) {
        return new ServiceAccessTokenSignatureVerifier(
                keyResolver(loadBalancer), Clock.systemUTC(), issuer, "saasforge-api", Duration.ofSeconds(30));
    }

    @Bean
    UserAccessTokenContextRevocationChecker receiverUserAccessTokenRevocationChecker(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment) {
        return new RedisReceiverTokenRevocationChecker(redis, environment)::isUserTokenRevoked;
    }

    @Bean
    ServiceAccessTokenRevocationChecker receiverServiceAccessTokenRevocationChecker(
            StringRedisTemplate redis,
            @Value("${saasforge.environment:dev}") String environment) {
        return new RedisReceiverTokenRevocationChecker(redis, environment)::isServiceTokenRevoked;
    }

    private static ServiceJwtVerificationKeyResolver keyResolver(LoadBalancerClient loadBalancer) {
        return kid -> {
            ServiceInstance instance = loadBalancer.choose("iam-service");
            if (instance == null) {
                return Optional.empty();
            }
            JwksResponse response = RestClient.create(instance.getUri())
                    .get()
                    .uri("/.well-known/jwks.json")
                    .retrieve()
                    .body(JwksResponse.class);
            if (response == null || response.keys() == null) {
                throw new IllegalStateException("IAM JWKS 响应不合法");
            }
            return response.keys().stream()
                    .filter(key -> "RSA".equals(key.kty())
                            && "RS256".equals(key.alg())
                            && kid.equals(key.kid()))
                    .findFirst()
                    .map(key -> new ServiceJwtVerificationKey(key.kid(), key.n(), key.e()));
        };
    }

    private record JwksResponse(List<JwkResponse> keys) {
    }

    private record JwkResponse(String kty, String alg, String use, String kid, String n, String e) {
    }
}
