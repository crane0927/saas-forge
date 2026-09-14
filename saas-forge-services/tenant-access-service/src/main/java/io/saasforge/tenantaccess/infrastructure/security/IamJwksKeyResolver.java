package io.saasforge.tenantaccess.infrastructure.security;

import io.saasforge.sdk.auth.ServiceJwtVerificationKey;
import io.saasforge.sdk.auth.ServiceJwtVerificationKeyResolver;
import java.util.List;
import java.util.Optional;
import org.springframework.web.client.RestClient;

/** 从 IAM 权威 JWKS 解析当前可用的 RS256 验证公钥；调用失败时由上层失败关闭。 */
public final class IamJwksKeyResolver implements ServiceJwtVerificationKeyResolver {
    private final RestClient iam;

    public IamJwksKeyResolver(RestClient iam) {
        this.iam = iam;
    }

    @Override
    public Optional<ServiceJwtVerificationKey> findByKid(String kid) {
        JwksResponse response = iam.get().uri("/.well-known/jwks.json").retrieve().body(JwksResponse.class);
        if (response == null || response.keys() == null) {
            throw new IllegalStateException("IAM JWKS 响应不合法");
        }
        return response.keys().stream()
                .filter(key -> "RSA".equals(key.kty()) && "RS256".equals(key.alg()) && kid.equals(key.kid()))
                .findFirst()
                .map(key -> new ServiceJwtVerificationKey(key.kid(), key.n(), key.e()));
    }

    public record JwksResponse(List<JwkResponse> keys) {
    }

    public record JwkResponse(String kty, String alg, String use, String kid, String n, String e) {
    }
}
