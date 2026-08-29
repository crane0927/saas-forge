package io.saasforge.gateway.config;

import io.saasforge.sdk.auth.ServiceAccessTokenInvalidException;
import io.saasforge.sdk.auth.ServiceAccessTokenSignatureVerifier;
import java.util.List;
import java.util.UUID;

/** 验证 Service Access Token 后按安全优先级检查即时吊销与 operation 的 AND Scope。 */
final class GatewayServiceAccessTokenVerifier implements GatewayServiceTokenVerifier {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ServiceAccessTokenSignatureVerifier signatures;
    private final GatewayServiceTokenRevocationChecker revocations;

    GatewayServiceAccessTokenVerifier(
            ServiceAccessTokenSignatureVerifier signatures,
            GatewayServiceTokenRevocationChecker revocations) {
        if (signatures == null || revocations == null) {
            throw new IllegalArgumentException("Gateway Service Access Token 校验配置不合法");
        }
        this.signatures = signatures;
        this.revocations = revocations;
    }

    @Override
    public void verify(String authorization, List<String> requiredScopes) {
        if (authorization == null
                || !authorization.startsWith(BEARER_PREFIX)
                || authorization.length() == BEARER_PREFIX.length()
                || requiredScopes == null
                || requiredScopes.isEmpty()) {
            throw new GatewayServiceTokenInvalidException();
        }
        try {
            var claims = signatures.verify(authorization.substring(BEARER_PREFIX.length()));
            revocations.assertAllowed(claims.clientId(), claims.kid());
            if (!claims.scopes().containsAll(requiredScopes)) {
                throw new GatewayServiceTokenScopeInsufficientException();
            }
        } catch (GatewayTokenRevocationStatusUnavailableException
                | GatewayServiceTokenInvalidException
                | GatewayServiceTokenScopeInsufficientException exception) {
            throw exception;
        } catch (ServiceAccessTokenInvalidException exception) {
            throw new GatewayServiceTokenInvalidException(exception);
        }
    }
}

@FunctionalInterface
interface GatewayServiceTokenVerifier {
    void verify(String authorization, List<String> requiredScopes);
}

@FunctionalInterface
interface GatewayServiceTokenRevocationChecker {
    void assertAllowed(UUID clientId, String kid);
}

final class GatewayServiceTokenInvalidException extends RuntimeException {
    GatewayServiceTokenInvalidException() {
        super();
    }

    GatewayServiceTokenInvalidException(Throwable cause) {
        super(cause);
    }
}

final class GatewayServiceTokenScopeInsufficientException extends RuntimeException {
}
