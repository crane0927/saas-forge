package io.saasforge.gateway.config;

import io.saasforge.contracts.route.HttpRouteCatalog;
import io.saasforge.contracts.route.HttpRouteCatalogLoader;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class GatewayServiceTokenTestConfiguration {

    public static final String TEST_PATH = "/__test/service-access";
    public static final String VALID_BEARER = "Bearer gateway-service-valid";
    public static final String EXTRA_SCOPE_BEARER = "Bearer gateway-service-extra-scope";
    public static final String INSUFFICIENT_BEARER = "Bearer gateway-service-insufficient";
    public static final String REVOKED_BEARER = "Bearer gateway-service-revoked";
    public static final String UNAVAILABLE_BEARER = "Bearer gateway-service-unavailable";
    public static final List<String> REQUIRED_SCOPES = List.of("runtime:quota:write", "runtime:read");

    @Bean
    @Primary
    GatewayRouteCatalog gatewayServiceTestRouteCatalog() {
        List<HttpRouteCatalog.Route> routes = new ArrayList<>(HttpRouteCatalogLoader.load().routes());
        routes.add(new HttpRouteCatalog.Route(
                "verifyGatewayServiceAccess",
                HttpRouteCatalog.HttpMethod.GET,
                TEST_PATH,
                "entitlement-service",
                HttpRouteCatalog.CredentialRequirement.SERVICE_REQUIRED,
                REQUIRED_SCOPES));
        return new GatewayRouteCatalog(new HttpRouteCatalog(HttpRouteCatalogLoader.SUPPORTED_SCHEMA_VERSION, routes));
    }

    @Bean
    @Primary
    GatewayServiceTokenVerifier gatewayServiceOperationTokenVerifier() {
        return (authorization, requiredScopes) -> {
            if (!REQUIRED_SCOPES.equals(requiredScopes)) {
                throw new AssertionError("受控测试 operation 未传递确定性排序的 AND Scope");
            }
            if (VALID_BEARER.equals(authorization) || EXTRA_SCOPE_BEARER.equals(authorization)) {
                return;
            }
            if (INSUFFICIENT_BEARER.equals(authorization)) {
                throw new GatewayServiceTokenScopeInsufficientException();
            }
            if (UNAVAILABLE_BEARER.equals(authorization)) {
                throw new GatewayTokenRevocationStatusUnavailableException();
            }
            throw new GatewayServiceTokenInvalidException();
        };
    }
}
